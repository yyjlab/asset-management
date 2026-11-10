package com.acme.assetmanagement.integration.deployment;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import jakarta.validation.Validator;
import org.springframework.dao.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.acme.assetmanagement.integration.deployment.DeploymentRegistrationRequest.*;

@Service
public class DeploymentIntakeService {
    private final DeploymentIntakeRepository intakes;
    private final DeploymentCredentialService credentials;
    private final DeploymentAccessService access;
    private final DeploymentRegistrationService legacy;
    private final AssetService assets;
    private final LookupRepository lookups;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final Validator validator;
    private final TransactionTemplate transaction;

    public DeploymentIntakeService(DeploymentIntakeRepository intakes, DeploymentCredentialService credentials,
            DeploymentAccessService access, DeploymentRegistrationService legacy, AssetService assets,
            LookupRepository lookups, AuditLogService audit, ObjectMapper json, Validator validator,
            PlatformTransactionManager manager) {
        this.intakes = intakes; this.credentials = credentials; this.access = access; this.legacy = legacy;
        this.assets = assets; this.lookups = lookups; this.audit = audit; this.json = json; this.validator = validator;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(30);
    }

    public record AssetRef(Long id, String assetTag, Long version) {}
    public record Result(String externalRegistrationId, String result, AssetRef asset, String message) {}
    public record Entry(String externalRegistrationId, Instant receivedAt, Instant capturedAt, Operator declaredOperator,
            String credentialId, String state, int revision, String model, String cpu, String memory, String storage,
            String graphicsCard, String manufacturerSerialNumber, Long categoryId, AssetRef asset,
            Instant reviewedAt, String reviewedBy, String reviewNote) {}
    public record History(List<Entry> items, int page, int totalPages, long totalElements) {}
    public record Options(List<DeploymentRegistrationService.CategoryChoice> categories) {}
    public record Review(int revision, Long categoryId, String name, String model, String cpu, String memory,
            String storage, String graphicsCard, String manufacturerSerialNumber, String note) {}

    public Result receive(byte[] body) {
        var request = parseIntake(body, json);
        var principal = principal();
        String fingerprint = DeploymentCredentialService.hash(json.writeValueAsString(request));
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transaction.execute(status -> {
                    if (!access.isEnabled()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRATION_DISABLED", "接入未启用");
                    credentials.requireActiveLocked(principal);
                    var previous = intakes.locked(request.externalRegistrationId()).orElse(null);
                    if (previous != null) {
                        if (!previous.fingerprint.equals(fingerprint))
                            throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "相同标识不能携带不同内容");
                        return result(previous);
                    }
                    var row = new DeploymentIntake(); row.externalId = request.externalRegistrationId();
                    row.credentialId = principal.credentialId(); row.fingerprint = fingerprint; row.receivedAt = Instant.now();
                    row.requestJson = new String(body, StandardCharsets.UTF_8);
                    intakes.saveAndFlush(row);
                    audit(row, "pending_review", request);
                    return result(row);
                });
            } catch (DataIntegrityViolationException | ConcurrencyFailureException exception) {
                if (attempt == 2) throw error(HttpStatus.CONFLICT, "CONCURRENT_RETRY", "请保留原标识和内容重试");
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    @Transactional(readOnly = true)
    public Result status(String id) {
        principal(); return result(find(id));
    }

    @Transactional(readOnly = true)
    public History list(int page, String state) {
        reviewer();
        if (page < 0 || page > 100000 || !Set.of("pending_review", "approved", "rejected").contains(state))
            throw error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "页码或状态无效");
        var rows = intakes.findByStateOrderByReceivedAtDescExternalIdDesc(state, PageRequest.of(page, 20));
        return new History(rows.stream().map(this::entry).toList(), page, rows.getTotalPages(), rows.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Options options() { reviewer(); return new Options(legacy.connectionInfo().categories()); }

    public Entry review(String id, byte[] body, boolean approve) {
        String username = reviewer();
        Review review = parseReview(body, approve);
        String fingerprint = DeploymentCredentialService.hash(json.writeValueAsString(review));
        return transaction.execute(status -> {
            var row = intakes.locked(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "待审核记录不存在"));
            String target = approve ? "approved" : "rejected";
            if (!row.state.equals("pending_review")) {
                if (row.state.equals(target) && row.revision == review.revision() + 1 && fingerprint.equals(row.reviewFingerprint))
                    return entry(row);
                throw error(HttpStatus.CONFLICT, "REVIEW_CONFLICT", "该记录已审核，请刷新；不能更改终态");
            }
            if (row.revision != review.revision()) throw error(HttpStatus.CONFLICT, "REVISION_CONFLICT", "审核版本已变化，请刷新");
            if (approve) {
                var company = legacy.dictionary(LookupType.COMPANY, "微丰启");
                var location = legacy.dictionary(LookupType.LOCATION, "公司");
                var available = legacy.dictionary(LookupType.STATUS, "当前可用", "可领用");
                var category = lookups.findById(review.categoryId()).filter(v -> v.getType() == LookupType.CATEGORY
                        && !v.isDisabled() && legacy.profile(v) == AssetProfile.COMPUTER)
                        .orElseThrow(() -> error(HttpStatus.UNPROCESSABLE_ENTITY, "CATEGORY_INVALID", "须选择启用的电脑分类"));
                if (Set.of("unknown", "system product name", "to be filled by o.e.m.", "default string", "未知")
                        .contains(review.model().toLowerCase(Locale.ROOT)))
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "须填写真实型号");
                if (review.graphicsCard() != null) {
                    var graphics = review.graphicsCard().toLowerCase(Locale.ROOT).replace(" ", "");
                    if (graphics.contains("basicdisplay") || graphics.contains("basicrender") || graphics.contains("基本显示")
                            || graphics.contains("基本呈现") || graphics.contains("standardvga") || graphics.contains("标准vga"))
                        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "基础显示驱动不是真实显卡型号，未确认时请留空");
                }
                if (review.manufacturerSerialNumber() != null) legacy.requireDescriptiveSerialSchema();
                var value = new AssetRequest(null, review.name(), null, review.cpu(), review.memory(), review.storage(),
                        review.graphicsCard(), review.manufacturerSerialNumber(), null, null, null, null, company.getId(),
                        null, review.model(), category.getId(), available.getId(), location.getId(), null, null,
                        false, null, null, List.of(), null, List.of(), null, List.of(), List.of(), List.of(), null, null);
                var violations = validator.validate(value);
                if (!violations.isEmpty()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", violations.iterator().next().getMessage());
                var created = assets.createFromDeployment(value);
                row.assetId = created.id(); row.assetTag = created.assetTag(); row.assetVersion = created.version();
                row.reviewJson = json.writeValueAsString(review);
            }
            row.state = target; row.revision++; row.reviewedAt = Instant.now(); row.reviewedBy = username;
            row.reviewNote = review.note(); row.reviewFingerprint = fingerprint;
            intakes.saveAndFlush(row);
            audit(row, target, parseIntake(row.requestJson.getBytes(StandardCharsets.UTF_8), json));
            return entry(row);
        });
    }

    private Review parseReview(byte[] body, boolean approve) {
        if (body.length > DeploymentSecurityConfig.MAX_BYTES) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "请求不能超过32KiB");
        final Map<?, ?> root;
        try { root = object(json.readerFor(Object.class).with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).readValue(body), "request"); }
        catch (DeploymentApiException e) { throw e; }
        catch (RuntimeException e) { throw error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "请求必须为JSON对象"); }
        if (approve) keys(root, "request", "revision", "categoryId", "name", "model", "cpu", "memory", "storage", "graphicsCard", "manufacturerSerialNumber", "note");
        else keys(root, "request", "revision", "note");
        Object revision = root.get("revision");
        if (!(revision instanceof Integer number) || number < 0)
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "revision 须为非负整数");
        return new Review((Integer)revision, approve ? positive(root.get("categoryId"), "categoryId") : null,
                text(root, "name", 160, approve), text(root, "model", 120, approve), text(root, "cpu", 200, false),
                text(root, "memory", 200, false), text(root, "storage", 200, false), text(root, "graphicsCard", 200, false),
                text(root, "manufacturerSerialNumber", 160, false), text(root, "note", 500, !approve));
    }

    private DeploymentIntake find(String id) {
        return intakes.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "待审核记录不存在"));
    }
    private DeploymentPrincipal principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof DeploymentPrincipal p)
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(DeploymentPrincipal.AUTHORITY)))
            throw error(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅专用服务凭据可访问");
        return p;
    }
    private String reviewer() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AccountPrincipal)
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN")))
            throw error(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅超级管理员可审核");
        return auth.getName();
    }
    private AssetRef asset(DeploymentIntake row) {
        return row.assetId == null ? null : new AssetRef(row.assetId, row.assetTag, row.assetVersion);
    }
    private Result result(DeploymentIntake row) { return new Result(row.externalId, row.state, asset(row), row.reviewNote); }
    private Entry entry(DeploymentIntake row) {
        var request = parseIntake(row.requestJson.getBytes(StandardCharsets.UTF_8), json);
        var projection = legacy.projection(request.snapshot(), new ArrayList<>(), false);
        Review r = row.reviewJson == null ? null : json.readValue(row.reviewJson, Review.class);
        return new Entry(row.externalId, row.receivedAt, request.capturedAt(), request.operator(), row.credentialId, row.state,
                row.revision, r == null ? request.snapshot().model() : r.model(), r == null ? request.snapshot().cpu() : r.cpu(),
                r == null ? request.snapshot().memory() : r.memory(), r == null ? projection.get("storage") : r.storage(),
                r == null ? projection.get("graphicsCard") : r.graphicsCard(),
                r == null ? request.snapshot().manufacturerSerialNumber() : r.manufacturerSerialNumber(),
                r == null ? request.categoryId() : r.categoryId(), asset(row), row.reviewedAt, row.reviewedBy, row.reviewNote);
    }
    private void audit(DeploymentIntake row, String state, DeploymentRegistrationRequest request) {
        var changes = new LinkedHashMap<String, Object>();
        changes.put("source", "AD装机平台"); changes.put("credentialId", row.credentialId);
        changes.put("externalRegistrationId", row.externalId); changes.put("declaredOperator", request.operator());
        changes.put("result", state); changes.put("reviewedBy", row.reviewedBy); changes.put("reviewNote", row.reviewNote);
        audit.success(AuditAction.AD_DEPLOYMENT_REGISTER, "AD_DEPLOYMENT_INTAKE", row.assetId, row.externalId,
                "AD装机平台：" + state, changes);
    }
    private DeploymentApiException error(HttpStatus status, String code, String message) {
        return new DeploymentApiException(status, code, message);
    }
}
