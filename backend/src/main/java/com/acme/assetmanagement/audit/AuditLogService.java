package com.acme.assetmanagement.audit;

import com.acme.assetmanagement.security.AccountPrincipal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional
public class AuditLogService {
    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void success(AuditAction action, String targetType, Long targetId, String targetLabel,
                        String summary, Map<String, ?> changes) {
        Actor actor = currentActor();
        save(actor, action, AuditResult.SUCCESS, targetType, targetId, targetLabel, summary, changes);
    }

    public void authenticationSuccess(AccountPrincipal principal, AuditAction action, String summary) {
        try {
            save(new Actor(principal.id(), principal.username(), principal.role().name()), action,
                    AuditResult.SUCCESS, "AUTH", principal.id(), principal.username(), summary, Map.of());
        } catch (RuntimeException exception) {
            log.error("Unable to write authentication audit log", exception);
        }
    }

    public void authenticationFailure(String username, String summary) {
        try {
            save(new Actor(null, safe(username, "未知账号", 120), null), AuditAction.LOGIN_FAILED,
                    AuditResult.FAILURE, "AUTH", null, safe(username, "未知账号", 120), summary, Map.of());
        } catch (RuntimeException exception) {
            log.error("Unable to write failed-login audit log", exception);
        }
    }

    public Map<String, Object> diff(Map<String, ?> before, Map<String, ?> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : after.keySet()) {
            Object oldValue = before.get(key);
            Object newValue = after.get(key);
            if (!Objects.equals(oldValue, newValue)) {
                changes.put(key, Map.of("before", oldValue == null ? "" : oldValue,
                        "after", newValue == null ? "" : newValue));
            }
        }
        return changes;
    }

    private void save(Actor actor, AuditAction action, AuditResult result, String targetType, Long targetId,
                      String targetLabel, String summary, Map<String, ?> changes) {
        AuditLog entry = new AuditLog();
        entry.setActorUserId(actor.id());
        entry.setActorUsername(safe(actor.username(), "系统", 120));
        entry.setActorRole(safe(actor.role(), null, 40));
        entry.setAction(action);
        entry.setResult(result);
        entry.setTargetType(safe(targetType, "SYSTEM", 50));
        entry.setTargetId(targetId);
        entry.setTargetLabel(safe(targetLabel, null, 240));
        entry.setSummary(safe(summary, action.getLabel(), 600));
        entry.setChangesJson(toJson(changes == null ? Map.of() : changes));
        HttpServletRequest request = currentRequest();
        if (request != null) {
            entry.setIpAddress(resolveIp(request));
            entry.setUserAgent(safe(request.getHeader("User-Agent"), null, 500));
        }
        repository.save(entry);
    }

    private Actor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            return new Actor(principal.id(), principal.username(), principal.role().name());
        }
        if (authentication != null && authentication.getPrincipal() instanceof
                com.acme.assetmanagement.integration.deployment.DeploymentPrincipal principal) {
            return new Actor(null, principal.getName(), "INTEGRATION");
        }
        return new Actor(null, "系统", null);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        // Production's forwarded-header filter uses the header overwritten by our ingress.
        return safe(request.getRemoteAddr(), null, 80);
    }

    private String toJson(Map<String, ?> changes) {
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法序列化操作日志", exception);
        }
    }

    private static String safe(String value, String fallback, int maxLength) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private record Actor(Long id, String username, String role) {}
}
