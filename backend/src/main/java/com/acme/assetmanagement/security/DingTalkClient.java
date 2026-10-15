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

    /** Use the application's enterprise token, not a user's editable nickname, to verify membership/name. */
    public String employeeName(String unionId) {
        try {
            Map<?, ?> token = client.post().uri("/v1.0/oauth2/accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("appKey", properties.clientId(), "appSecret", properties.clientSecret()))
                    .retrieve().body(Map.class);
            String appToken = requiredString(token, "accessToken");
            Map<?, ?> membership = client.post().uri("https://oapi.dingtalk.com/topapi/user/getbyunionid?access_token={token}", appToken)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("unionid", unionId))
                    .retrieve().body(Map.class);
            Map<?, ?> member = successfulResult(membership);
            if (!(member.get("contact_type") instanceof Number type) || type.intValue() != 0) throw notEmployee();
            String userId = requiredString(member, "userid");
            Map<?, ?> detail = client.post().uri("https://oapi.dingtalk.com/topapi/v2/user/get?access_token={token}", appToken)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("userid", userId, "language", "zh_CN"))
                    .retrieve().body(Map.class);
            Map<?, ?> employee = successfulResult(detail);
            if (!userId.equals(requiredString(employee, "userid")) || Boolean.FALSE.equals(employee.get("active"))) throw notEmployee();
            String name = requiredString(employee, "name").trim();
            if (name.length() > 80) throw notEmployee();
            return name;
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "无法核验企业成员，请管理员检查钉钉通讯录权限和应用可见范围");
        }
    }

    private Map<?, ?> successfulResult(Map<?, ?> response) {
        if (response == null || !(response.get("errcode") instanceof Number code) || code.intValue() != 0
                || !(response.get("result") instanceof Map<?, ?> result)) throw notEmployee();
        return result;
    }
    private ApiException notEmployee() {
        return new ApiException(HttpStatus.FORBIDDEN, "企业成员核验失败，请确认属于本企业且在应用通讯录权限范围内");
    }

    private String requiredString(Map<?, ?> body, String key) {
        if (body == null || !(body.get(key) instanceof String value) || value.isBlank()) throw unavailable();
        return value;
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "钉钉身份验证失败，请检查应用配置或稍后重试");
    }
}
