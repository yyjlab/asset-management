package com.acme.assetmanagement.workflow;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class DingTalkTaskNotifier {
    private final WorkflowNotificationProperties properties;
    private final RestClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public DingTalkTaskNotifier(WorkflowNotificationProperties properties) {
        this.properties = properties;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(8)); this.client = RestClient.builder().requestFactory(factory).build();
    }
    DingTalkTaskNotifier(WorkflowNotificationProperties properties, RestClient client) { this.properties = properties; this.client = client; }
    public boolean send(TaskNotifications.Delivery delivery) {
        if (!properties.configured()) return false;
        try {
            String timestamp = Long.toString(System.currentTimeMillis());
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(mac.doFinal(
                    (timestamp + "\n" + properties.secret()).getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8);
            var uri = URI.create(properties.webhook() + "&timestamp=" + timestamp + "&sign=" + sign);
            var result = client.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("msgtype", "text", "text", Map.of("content", delivery.title() + "\n" + delivery.content())))
                    .retrieve().body(Map.class);
            return result != null && result.get("errcode") instanceof Number code && code.intValue() == 0;
        } catch (Exception exception) {
            // Provider response bodies and URLs can contain credentials; never log them.
            return false;
        }
    }
}
