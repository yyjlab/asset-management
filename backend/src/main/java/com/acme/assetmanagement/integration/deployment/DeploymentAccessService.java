package com.acme.assetmanagement.integration.deployment;

import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import com.acme.assetmanagement.setting.SystemSetting;
import com.acme.assetmanagement.setting.SystemSettingRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DeploymentAccessService {
    static final String KEY = "AD_DEPLOYMENT_ACCESS";
    private final SystemSettingRepository settings;
    private final DeploymentProperties bootstrap;
    private final AuditLogService audit;

    public DeploymentAccessService(SystemSettingRepository settings, DeploymentProperties bootstrap, AuditLogService audit) {
        this.settings = settings; this.bootstrap = bootstrap; this.audit = audit;
    }

    public record Access(boolean enabled, boolean allowHttp) {}

    public Access current() {
        return settings.findById(KEY).map(row -> new Access(
                "HTTPS".equals(row.getValue()) || "INTERNAL_HTTP".equals(row.getValue()),
                "INTERNAL_HTTP".equals(row.getValue())))
                .orElseGet(() -> new Access(bootstrap.isEnabled(), false));
    }

    public boolean isEnabled() { return current().enabled(); }
    public boolean allowsRequest(boolean secure) { return secure || current().allowHttp(); }

    @Transactional
    public Access save(boolean enabled, boolean allowHttp) {
        String value = !enabled ? "DISABLED" : allowHttp ? "INTERNAL_HTTP" : "HTTPS";
        var row = settings.findById(KEY).orElseGet(() -> new SystemSetting(KEY, value));
        row.setValue(value); settings.saveAndFlush(row);
        audit.success(AuditAction.SETTING_UPDATE, "AD_DEPLOYMENT_ACCESS", null, KEY,
                "修改装机平台接入设置", Map.of("启用", enabled, "允许内网HTTP", enabled && allowHttp));
        return current();
    }
}
