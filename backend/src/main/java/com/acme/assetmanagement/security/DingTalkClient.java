package com.acme.assetmanagement.security;

import com.acme.assetmanagement.common.ApiException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Component
@EnableConfigurationProperties(DingTalkProperties.class)
public class DingTalkClient {
    private final DingTalkProperties properties;
    private final RestClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public DingTalkClient(DingTalkProperties properties) {
        this.properties = properties;
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.timeoutSeconds()));
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).baseUrl("https://api.dingtalk.com").build();
    }

    // Package-private constructor keeps provider calls testable without real credentials or network.
    DingTalkClient(DingTalkProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    public String unionId(String code) {
        try {
            Map<?, ?> token = client.post().uri("/v1.0/oauth2/userAccessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("clientId", properties.clientId(), "clientSecret", properties.clientSecret(),
                            "code", code, "grantType", "authorization_code"))
                    .retrieve().body(Map.class);
            String accessToken = requiredString(token, "accessToken");
            Map<?, ?> user = client.get().uri("/v1.0/contact/users/me")
                    .header("x-acs-dingtalk-access-token", accessToken).retrieve().body(Map.class);
            String identity = requiredString(user, "unionId");
            if (identity.length() > 256) throw unavailable();
            return identity;
        } catch (RestClientException exception) {
            // Provider errors may contain credentials/tokens. Never expose their raw bodies or exception messages.
            throw unavailable();
        }
    }

    private String requiredString(Map<?, ?> body, String key) {
        if (body == null || !(body.get(key) instanceof String value) || value.isBlank()) throw unavailable();
        return value;
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "钉钉身份验证失败，请检查应用配置或稍后重试");
    }
}
