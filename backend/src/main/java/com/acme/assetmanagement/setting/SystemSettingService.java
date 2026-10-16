package com.acme.assetmanagement.setting;

import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import com.acme.assetmanagement.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;

@Service
@Transactional
public class SystemSettingService {
    private static final String QR_BASE_URL = "QR_BASE_URL";

    private final SystemSettingRepository repository;
    private final String bootstrapQrBaseUrl;
    private final AuditLogService auditLogService;

    public SystemSettingService(SystemSettingRepository repository,
                                @Value("${app.public-base-url:}") String bootstrapQrBaseUrl,
                                AuditLogService auditLogService) {
        this.repository = repository;
        this.bootstrapQrBaseUrl = normalizeOptional(bootstrapQrBaseUrl);
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public QrSettingResponse getQrSetting() {
        return repository.findById(QR_BASE_URL)
                .map(setting -> new QrSettingResponse(setting.getValue(), true, "DATABASE", setting.getUpdatedAt()))
                .orElseGet(() -> new QrSettingResponse(bootstrapQrBaseUrl, !bootstrapQrBaseUrl.isBlank(),
                        bootstrapQrBaseUrl.isBlank() ? "NONE" : "ENVIRONMENT", null));
    }

    @Transactional(readOnly = true)
    public String requireQrBaseUrl() {
        QrSettingResponse setting = getQrSetting();
        if (!setting.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "尚未配置二维码访问地址，请由超级管理员前往设置页面配置");
        }
        return setting.qrBaseUrl();
    }

    public QrSettingResponse updateQrBaseUrl(String value) {
        String normalized = validateAndNormalize(value);
        String before = getQrSetting().qrBaseUrl();
        SystemSetting setting = repository.findById(QR_BASE_URL)
                .orElseGet(() -> new SystemSetting(QR_BASE_URL, normalized));
        setting.setValue(normalized);
        SystemSetting saved = repository.save(setting);
        auditLogService.success(AuditAction.SETTING_UPDATE, "SETTING", null, "二维码访问地址",
                "修改二维码访问地址", auditLogService.diff(java.util.Map.of("二维码访问地址", before),
                        java.util.Map.of("二维码访问地址", normalized)));
        return new QrSettingResponse(saved.getValue(), true, "DATABASE", saved.getUpdatedAt());
    }

    private static String validateAndNormalize(String value) {
        String normalized = normalizeOptional(value);
        if (normalized.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请输入二维码访问地址");
        }
        try {
            URI uri = URI.create(normalized);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "二维码访问地址不能包含账号信息、查询参数或片段");
            }
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "二维码访问地址必须是完整的 http:// 或 https:// 地址");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    public record QrSettingResponse(String qrBaseUrl, boolean configured, String source, LocalDateTime updatedAt) {}
}
