package com.acme.assetmanagement.integration.deployment;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.lookup.*;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class DeploymentRegistrationService {
    public static final String SOURCE = "AD 装机平台";
    private final AssetRepository assets;
    private final AssetService assetService;
    private final LookupRepository lookups;
    private final AssetTagSequenceRepository sequences;
    private final DeploymentRegistrationRepository registrations;
    private final DeploymentCredentialService credentials;
    private final DeploymentProperties properties;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DeploymentRegistrationService(AssetRepository assets, AssetService assetService, LookupRepository lookups,
            AssetTagSequenceRepository sequences, DeploymentRegistrationRepository registrations,
            DeploymentCredentialService credentials, DeploymentProperties properties, AuditLogService audit,
            ObjectMapper json, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.assets = assets; this.assetService = assetService; this.lookups = lookups; this.sequences = sequences;
        this.registrations = registrations; this.credentials = credentials; this.properties = properties;
        this.audit = audit; this.json = json; this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(30);
    }

    public DeploymentRegistrationResult register(DeploymentRegistrationRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof DeploymentPrincipal principal)
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(DeploymentPrincipal.AUTHORITY)))
            throw error(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅专用登记凭据可以访问");
        String requestJson = json.writeValueAsString(request);
        String fingerprint = DeploymentCredentialService.hash(requestJson);
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return transaction.execute(status -> receive(request, requestJson, fingerprint, principal)); }
            catch (DataIntegrityViolationException | ConcurrencyFailureException exception) {
                // A concurrent normal create may win the unique asset-tag insert. Re-read only after rollback.
                if (attempt == 2) throw error(HttpStatus.CONFLICT, "CONCURRENT_RETRY", "存在并发登记或字典变更，请保留原登记标识和内容后重试");
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private DeploymentRegistrationResult receive(DeploymentRegistrationRequest request, String requestJson,
                                                  String fingerprint, DeploymentPrincipal principal) {
        if (!properties.isEnabled()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRATION_DISABLED", "登记接口未启用");
        credentials.requireActiveLocked(principal);
        // Reuse the existing cluster-wide allocator lock; the journal and asset commit atomically.
        sequences.findLockedByDate(AssetTagGenerator.ALLOCATION_LOCK_DATE)
                .orElseThrow(() -> error(HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_NOT_READY", "资产分配锁尚未初始化"));
        var previous = registrations.findById(request.externalRegistrationId()).orElse(null);
        if (previous != null) {
            if (!previous.fingerprint.equals(fingerprint))
                throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "相同外部登记标识不能携带不同内容；请使用明确的新登记版本");
            var asset = assets.findByExactTagForDeployment(previous.assetTag).orElse(null);
            if (asset == null || !asset.getId().equals(previous.assetId))
                throw error(HttpStatus.GONE, "ASSET_NO_LONGER_EXISTS", "上次关联资产已被删除或替换，不能通过重试重新创建");
            return json.readValue(previous.responseJson, DeploymentRegistrationResult.class).replay();
        }
        var warnings = new ArrayList<String>();
        var incoming = projection(request.snapshot(), warnings);
        var existing = assets.findByExactTagForDeployment(request.assetTag()).orElse(null);
        DeploymentRegistrationResult result;
        if (existing == null) {
            var company = dictionary(LookupType.COMPANY, "微丰启");
            var location = dictionary(LookupType.LOCATION, "公司");
            var available = dictionary(LookupType.STATUS, "当前可用");
            var category = lookups.findById(request.categoryId()).filter(value -> value.getType() == LookupType.CATEGORY)
                    .orElseThrow(() -> error(HttpStatus.UNPROCESSABLE_ENTITY, "CATEGORY_INVALID", "categoryId 必须引用实际电脑分类"));
            if (category.isDisabled() || profile(category) != AssetProfile.COMPUTER)
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CATEGORY_INVALID", "仅允许启用的电脑分类，不接受显示器或通用资产分类");
            if (request.snapshot().manufacturerSerialNumber() != null) requireDescriptiveSerialSchema();
            var value = new AssetRequest(request.assetTag(), request.name() == null ? request.snapshot().model() : request.name(),
                    null, incoming.get("cpu"), incoming.get("memory"), incoming.get("storage"), incoming.get("graphicsCard"),
                    request.snapshot().manufacturerSerialNumber(), null, null, null, null,
                    company.getId(), null, request.snapshot().model(), category.getId(), available.getId(), location.getId(),
                    null, null, false, null, null, List.of(), null, List.of(), null, List.of(), List.of(), List.of(), null, null);
            var created = assetService.createFromDeployment(value);
            result = new DeploymentRegistrationResult(request.externalRegistrationId(), SOURCE, "created",
                    new DeploymentRegistrationResult.AssetRef(created.id(), created.assetTag(), created.version()),
                    List.of(), List.copyOf(warnings), false, Instant.now());
        } else {
            var differences = new ArrayList<DeploymentRegistrationResult.Difference>();
            if (profile(existing.getCategory()) != AssetProfile.COMPUTER)
                differences.add(new DeploymentRegistrationResult.Difference("assetProfile", profile(existing.getCategory()).name(), "COMPUTER"));
            Map<String, String> current = new LinkedHashMap<>();
            current.put("model", existing.getModel().getName()); current.put("cpu", existing.getCpu());
            current.put("memory", existing.getMemory()); current.put("storage", existing.getStorage());
            current.put("graphicsCard", existing.getGraphicsCard());
            incoming.forEach((field, value) -> {
                if (!Objects.equals(compare(current.get(field)), compare(value)))
                    differences.add(new DeploymentRegistrationResult.Difference(field, current.get(field), value));
            });
            result = new DeploymentRegistrationResult(request.externalRegistrationId(), SOURCE,
                    differences.isEmpty() ? "already_exists" : "configuration_conflict",
                    new DeploymentRegistrationResult.AssetRef(existing.getId(), existing.getAssetTag(), existing.getVersion()),
                    List.copyOf(differences), List.copyOf(warnings), false, Instant.now());
        }
        var journal = new DeploymentRegistration(); journal.externalId = request.externalRegistrationId();
        journal.fingerprint = fingerprint; journal.credentialId = principal.credentialId(); journal.assetTag = request.assetTag();
        journal.assetId = result.asset().id(); journal.createdAt = result.observedAt();
        journal.requestJson = requestJson; journal.responseJson = json.writeValueAsString(result);
        registrations.saveAndFlush(journal);
        var changes = new LinkedHashMap<String, Object>();
        changes.put("source", SOURCE); changes.put("credentialId", principal.credentialId());
        changes.put("credentialName", principal.label()); changes.put("externalRegistrationId", request.externalRegistrationId());
        changes.put("declaredOperator", request.operator()); changes.put("capturedAt", request.capturedAt().toString());
        changes.put("snapshot", request.snapshot()); changes.put("result", result.result()); changes.put("differences", result.differences());
        audit.success(AuditAction.AD_DEPLOYMENT_REGISTER, "ASSET", result.asset().id(), result.asset().assetTag(),
                "AD 装机平台登记：" + result.result() + "；操作人由发送端声明，不作为领用人", changes);
        return result;
    }

    private LookupValue dictionary(LookupType type, String name) {
        var values = lookups.findByTypeOrderByNameAsc(type).stream()
                .filter(value -> value.getName().trim().equalsIgnoreCase(name)).toList();
        if (values.size() != 1 || values.getFirst().isDisabled())
            throw error(HttpStatus.CONFLICT, "DEFAULT_DICTIONARY_INVALID", type.getLabel() + "“" + name + "”须存在唯一且启用的字典记录；未自动新建");
        return values.getFirst();
    }
    private AssetProfile profile(LookupValue category) {
        return category.getAssetProfile() == null ? AssetProfile.infer(category.getName()) : category.getAssetProfile();
    }
    private Map<String, String> projection(DeploymentRegistrationRequest.Snapshot snapshot, List<String> warnings) {
        if (Set.of("unknown", "system product name", "to be filled by o.e.m.", "default string", "未知").contains(compare(snapshot.model())))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "snapshot.model 未提供真实型号，不能编造设备型号");
        var values = new LinkedHashMap<String, String>();
        values.put("model", snapshot.model()); values.put("cpu", snapshot.cpu()); values.put("memory", snapshot.memory());
        String storage = snapshot.internalDisks().stream().map(disk ->
                (disk.model() == null ? "" : disk.model() + " ") +
                        BigDecimal.valueOf(disk.capacityBytes()).divide(BigDecimal.valueOf(1073741824L), 2, RoundingMode.HALF_UP)
                                .stripTrailingZeros().toPlainString() + " GiB (" + disk.busType() + ")")
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(java.util.stream.Collectors.joining("; "));
        values.put("storage", storage.isEmpty() ? null : bounded(storage, "snapshot.internalDisks"));
        var realGraphics = snapshot.graphicsCards().stream().filter(value -> {
            String name = value.toLowerCase(Locale.ROOT).replace(" ", "");
            boolean placeholder = name.contains("basicdisplay") || name.contains("basicrender") || name.contains("基本显示")
                    || name.contains("基本顯示") || name.contains("基本呈现") || name.contains("基本呈現")
                    || name.contains("基本渲染") || name.equals("standardvgagraphicsadapter") || name.equals("标准vga图形适配器")
                    || name.equals("unknown") || name.equals("未知");
            if (placeholder && !warnings.contains("BASIC_DISPLAY_DRIVER_IGNORED")) warnings.add("BASIC_DISPLAY_DRIVER_IGNORED");
            return !placeholder;
        }).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        values.put("graphicsCard", realGraphics.isEmpty() ? null : bounded(String.join(" + ", realGraphics), "snapshot.graphicsCards"));
        if (snapshot.internalDisks().isEmpty()) warnings.add("INTERNAL_DISKS_NOT_COLLECTED");
        return values;
    }
    private String bounded(String value, String field) {
        if (value.length() > 200) throw new DeploymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                field + " 汇总超过现有资产字段200字符上限，不能静默截断", List.of(field));
        return value;
    }
    private String compare(String value) { return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT); }
    private void requireDescriptiveSerialSchema() {
        Boolean unique = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            var metadata = connection.getMetaData();
            String table = metadata.storesUpperCaseIdentifiers() ? "ASSETS" : "assets";
            Map<String, List<String>> indexes = new HashMap<>();
            try (var rows = metadata.getIndexInfo(connection.getCatalog(), null, table, true, false)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME"), column = rows.getString("COLUMN_NAME");
                    if (name != null && column != null) indexes.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column.toLowerCase(Locale.ROOT));
                }
            }
            return indexes.values().stream().anyMatch(columns -> columns.equals(List.of("manufacturer_serial_number")));
        });
        if (Boolean.TRUE.equals(unique)) throw error(HttpStatus.SERVICE_UNAVAILABLE, "SN_SCHEMA_MIGRATION_REQUIRED",
                "现有数据库仍将 SN 设为唯一，请先审核并执行专用迁移；未创建资产");
    }
    private DeploymentApiException error(HttpStatus status, String code, String message) {
        return new DeploymentApiException(status, code, message);
    }
}
