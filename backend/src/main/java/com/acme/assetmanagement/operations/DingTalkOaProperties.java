package com.acme.assetmanagement.operations;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.*;

@ConfigurationProperties("app.dingtalk-oa")
public record DingTalkOaProperties(boolean enabled, boolean streamEnabled, String corpId,
        String issueProcessCode, String returnProcessCode, String itApproverUserIds, Long agentId) {
    public DingTalkOaProperties {
        if (enabled && (blank(corpId) || blank(issueProcessCode) || blank(returnProcessCode)
                || blank(itApproverUserIds) || issueProcessCode.equals(returnProcessCode)))
            throw new IllegalArgumentException("启用钉钉 OA 需配置企业ID、两个不同的审批模板及实物审批人 userid");
    }
    public Set<String> approvers() {
        return itApproverUserIds == null ? Set.of() : new HashSet<>(Arrays.asList(itApproverUserIds.trim().split("\\s*,\\s*")));
    }
    public String processCode(String kind) { return "ISSUE".equals(kind) ? issueProcessCode : returnProcessCode; }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    @Override public String toString() { return "DingTalkOaProperties[enabled=" + enabled + "]"; }
}
