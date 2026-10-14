package com.acme.assetmanagement.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;

@ConfigurationProperties("app.dingtalk-login")
public record DingTalkProperties(boolean enabled, String clientId, String clientSecret,
                                 String redirectUri, long stateTtlSeconds, int timeoutSeconds) {
    public DingTalkProperties {
        if (enabled) {
            if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalArgumentException("启用钉钉登录需要设置 Client ID 和 Client Secret");
            }
            URI uri;
            try { uri = URI.create(redirectUri == null ? "" : redirectUri); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("钉钉回调地址格式错误"); }
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !"/api/v1/auth/dingtalk/callback".equals(uri.getPath())) {
                throw new IllegalArgumentException("钉钉回调地址必须是 http(s)://域名/api/v1/auth/dingtalk/callback");
            }
            if (stateTtlSeconds < 60 || stateTtlSeconds > 1800 || timeoutSeconds < 1 || timeoutSeconds > 60) {
                throw new IllegalArgumentException("钉钉 state 有效期需在60至1800秒，超时需在1至60秒");
            }
        }
    }

    @Override public String toString() { return "DingTalkProperties[enabled=" + enabled + "]"; }
}
