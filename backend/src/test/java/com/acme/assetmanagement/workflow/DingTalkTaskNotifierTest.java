package com.acme.assetmanagement.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DingTalkTaskNotifierTest {
    private static final String WEBHOOK = "https://oapi.dingtalk.com/robot/send?access_token=unit_test_placeholder";
    private static final String SECRET = "unit-test-placeholder-not-a-real-secret";
    @Test void configurationRejectsUntrustedTargetsAndRedactsSecrets() {
        assertTrue(new WorkflowNotificationProperties(true, WEBHOOK, SECRET).configured());
        assertFalse(new WorkflowNotificationProperties(false, WEBHOOK, SECRET).configured());
        assertFalse(new WorkflowNotificationProperties(true, WEBHOOK, "").configured());
        for (var value : List.of("http://oapi.dingtalk.com/robot/send?access_token=unit_test_placeholder", WEBHOOK + "&foo=1",
                WEBHOOK.replace("oapi.dingtalk.com", "oapi.dingtalk.com.example.org"), WEBHOOK + "#fragment", WEBHOOK.replace("https://", "https://user@"), WEBHOOK.replace("/robot/send", "/other")))
            assertFalse(new WorkflowNotificationProperties(true, value, SECRET).configured(), value);
        var rendered = new WorkflowNotificationProperties(true, WEBHOOK, SECRET).toString();
        assertFalse(rendered.contains(SECRET)); assertFalse(rendered.contains("access_token"));
    }
    @Test void signsRequestAndRequiresProviderSuccessRatherThanHttpSuccess() throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var notifier = new DingTalkTaskNotifier(new WorkflowNotificationProperties(true, WEBHOOK, SECRET), builder.build());
        server.expect(request -> {
            assertEquals("https", request.getURI().getScheme()); assertEquals("oapi.dingtalk.com", request.getURI().getHost());
            var query = new HashMap<String, String>();
            for (String part : request.getURI().getRawQuery().split("&")) {
                var pair = part.split("=", 2); query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            try {
                var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                assertEquals(Base64.getEncoder().encodeToString(mac.doFinal((query.get("timestamp") + "\n" + SECRET).getBytes(StandardCharsets.UTF_8))), query.get("sign"));
            } catch (java.security.GeneralSecurityException error) { throw new AssertionError(error); }
        }).andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));
        assertTrue(notifier.send(new TaskNotifications.Delivery(1L, "lease", "测试", "不包含敏感详情"))); server.verify(); server.reset();
        server.expect(request -> {}).andRespond(withSuccess("{\"errcode\":310000,\"errmsg\":\"secret-provider-body\"}", MediaType.APPLICATION_JSON));
        assertFalse(notifier.send(new TaskNotifications.Delivery(1L, "lease", "测试", "不包含敏感详情"))); server.verify(); server.reset();
        server.expect(request -> {}).andRespond(withServerError());
        assertFalse(notifier.send(new TaskNotifications.Delivery(1L, "lease", "测试", "不包含敏感详情"))); server.verify();
    }
    @Test void disabledOrInvalidConfigurationDoesNotMakeNetworkRequest() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        assertFalse(new DingTalkTaskNotifier(new WorkflowNotificationProperties(false, WEBHOOK, SECRET), builder.build())
                .send(new TaskNotifications.Delivery(1L, "lease", "测试", "不会发送"))); server.verify();
    }
}
