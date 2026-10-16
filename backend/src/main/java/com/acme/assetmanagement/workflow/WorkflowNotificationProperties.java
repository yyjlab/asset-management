package com.acme.assetmanagement.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;

@ConfigurationProperties("app.workflow-notifications")
public record WorkflowNotificationProperties(boolean enabled, String webhook, String secret) {
    public boolean configured() {
        if (!enabled || webhook == null || secret == null || secret.isBlank() || secret.length() > 1024) return false;
        try {
            var uri = URI.create(webhook);
            return "https".equals(uri.getScheme()) && "oapi.dingtalk.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null && uri.getFragment() == null
                    && "/robot/send".equals(uri.getPath()) && uri.getRawQuery() != null
                    && uri.getRawQuery().matches("access_token=[A-Za-z0-9_-]{10,256}");
        } catch (IllegalArgumentException exception) { return false; }
    }
    @Override public String toString() { return "WorkflowNotificationProperties[enabled=" + enabled + "]"; }
}
