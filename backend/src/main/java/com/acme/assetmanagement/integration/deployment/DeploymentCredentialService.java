package com.acme.assetmanagement.integration.deployment;

import com.acme.assetmanagement.audit.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional
public class DeploymentCredentialService {
    private final DeploymentCredentialRepository credentials;
    private final AuditLogService audit;
    private final WindowsCollectionPackageRepository windows;
    public DeploymentCredentialService(DeploymentCredentialRepository credentials, AuditLogService audit, WindowsCollectionPackageRepository windows) {
        this.credentials = credentials; this.audit = audit; this.windows = windows;
    }
    public record Info(String id, String name, Instant createdAt, Instant expiresAt, Instant revokedAt, boolean active) {}
    public record Issued(Info credential, String token) {
        @Override public String toString() { return "Issued[credential=" + credential.id() + ", token=REDACTED]"; }
    }
    public Issued issue(String name, Instant expiresAt) {
        if (expiresAt == null || !expiresAt.isAfter(Instant.now()) || expiresAt.isAfter(Instant.now().plus(366, ChronoUnit.DAYS)))
            throw new DeploymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "凭据有效期须在未来且不超过366天");
        var row = new DeploymentCredential(); row.id = UUID.randomUUID().toString(); row.name = name.trim();
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        String token = "adp_" + row.id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        row.secretHash = hash(token); row.createdAt = Instant.now(); row.expiresAt = expiresAt;
        credentials.saveAndFlush(row);
        audit.success(AuditAction.SETTING_UPDATE, "AD_DEPLOYMENT_CREDENTIAL", null, row.id,
                "创建 AD 装机平台专用登记凭据", Map.of("凭据名称", row.name, "有效期", expiresAt.toString()));
        return new Issued(info(row), token);
    }
    @Transactional(readOnly = true)
    public List<Info> list() { return list(false); }
    @Transactional(readOnly = true)
    public List<Info> list(boolean windowsOnly) {
        var ids = windows.findAll().stream().map(v -> v.id).collect(java.util.stream.Collectors.toSet());
        return credentials.findAllByOrderByCreatedAtDesc().stream().filter(v -> ids.contains(v.id) == windowsOnly).map(this::info).toList();
    }
    public Info revoke(String id) {
        var row = credentials.locked(id).orElseThrow(() -> new DeploymentApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "凭据不存在"));
        if (row.revokedAt == null) {
            row.revokedAt = Instant.now();
            audit.success(AuditAction.SETTING_UPDATE, "AD_DEPLOYMENT_CREDENTIAL", null, row.id,
                    "撤销 AD 装机平台专用登记凭据", Map.of("凭据名称", row.name));
        }
        return info(row);
    }
    @Transactional(readOnly = true)
    public DeploymentPrincipal authenticate(String token) {
        if (token == null || !token.matches("adp_[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}")) return null;
        String id = token.substring(4, 40);
        var row = credentials.findById(id).orElse(null);
        if (row == null || !row.active() || !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                row.secretHash.getBytes(StandardCharsets.US_ASCII))) return null;
        return new DeploymentPrincipal(row.id, row.name);
    }
    public void requireActiveLocked(DeploymentPrincipal principal) {
        var row = credentials.locked(principal.credentialId()).orElse(null);
        if (row == null || !row.active())
            throw new DeploymentApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "专用凭据无效、已过期或已撤销");
    }
    private Info info(DeploymentCredential row) {
        return new Info(row.id, row.name, row.createdAt, row.expiresAt, row.revokedAt, row.active());
    }
    static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
