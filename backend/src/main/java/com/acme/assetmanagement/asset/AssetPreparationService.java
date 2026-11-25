package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.user.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class AssetPreparationService {
    private final AssetPreparationRepository preparations;
    private final AssetRepository assets;
    private final AssetAccess access;
    private final LookupRepository lookups;
    private final AuditLogService audit;
    public AssetPreparationService(AssetPreparationRepository preparations, AssetRepository assets, AssetAccess access,
                                   LookupRepository lookups, AuditLogService audit) {
        this.preparations = preparations; this.assets = assets; this.access = access; this.lookups = lookups; this.audit = audit;
    }
    public record Check(@NotBlank @Pattern(regexp = "UNCHECKED|PASS|ISSUE|NA") String result, @Size(max = 1000) String note) {}
    public record Action(@NotNull Long version, @NotNull Long assetVersion,
                         @NotBlank @Pattern(regexp = "START|SAVE|READY|MAINTENANCE|REQUEST_SCRAP|SCRAP") String action,
                         @Size(max = 1000) String note, @Valid Map<String, Check> checks) {}
    public record Overview(Long assetId, long version, long assetVersion, String state, String reason,
                           String previousHolder, LocalDateTime updatedAt) {}
    public record Event(int cycle, String actor, String action, String description, LocalDateTime occurredAt) {}
    public record Detail(Overview preparation, String note, Map<String, Check> checks, List<Event> events) {}

    public boolean hasHistory(Long assetId) { return preparations.findByAssetId(assetId).isPresent(); }

    public List<Overview> list() {
        access.permission(Permission.ASSET_VIEW);
        var scope = access.scope();
        var visible = assets.searchScoped("", scope.type() == com.acme.assetmanagement.user.DataScope.ALL,
                scope.type() == com.acme.assetmanagement.user.DataScope.SELF, scope.userId(),
                scope.type() == com.acme.assetmanagement.user.DataScope.DEPARTMENT,
                scope.departments().isEmpty() ? Set.of("") : scope.departments()).stream().filter(scope::allows).toList();
        if (visible.isEmpty()) return List.of();
        var byAsset = new HashMap<Long, AssetPreparation>();
        preparations.forAssets(visible.stream().map(Asset::getId).toList()).forEach(p -> byAsset.put(p.asset.getId(), p));
        return visible.stream().map(a -> overview(a, byAsset.get(a.getId()))).toList();
    }

    public Detail get(Long assetId) {
        access.permission(Permission.ASSET_VIEW);
        var a = assets.findById(assetId).orElseThrow(() -> fail("资产不存在")); access.require(a);
        return detail(a, preparations.findByAssetId(assetId).orElse(null));
    }

    @Transactional
    public Detail action(Long assetId, Action request) {
        access.permission(Permission.ASSET_VIEW); access.permission(Permission.ASSET_EDIT);
        var a = assets.findForRegistrationLink(assetId).orElseThrow(() -> fail("资产不存在")); access.require(a);
        if (a.isCheckedOut()) throw fail("设备仍在领用中，请先办理归还");
        if (a.getStatus().getName().contains("报废")) throw fail("已报废设备不能重新整备或配发");
        if (request.assetVersion() != a.getVersion()) throw fail("资产已变化，请刷新后核对");
        var p = preparations.findByAssetId(assetId).orElse(null);
        if (request.version() != (p == null ? -1 : p.version)) throw fail("整备记录已变化，请刷新后核对");
        if (p == null) { p = new AssetPreparation(); p.asset = a; }
        String note = clean(request.note());
        switch (request.action()) {
            case "START" -> { p.state = "IN_PROGRESS"; p.readyAssetVersion = null; }
            case "SAVE", "READY" -> {
                applyChecks(p, request.checks()); p.note = note;
                p.state = checks(p).values().stream().anyMatch(c -> c.result().equals("ISSUE")) ? "BLOCKED" : "IN_PROGRESS";
                p.readyAssetVersion = null;
                if (request.action().equals("READY")) {
                    if (a.isInMaintenanceOrScrapped()) throw fail("设备仍为维护或报废状态，请先完成处理并核实资产状态");
                    if (a.getAssignedUserId() != null || !clean(a.getAssignedTo()).isBlank()
                            || !Set.of("当前可用", "可领用").contains(a.getStatus().getName())) throw fail("请先核实领用信息及资产状态，只有未分配的可用设备才能配发");
                    if (a.getKnownDefects().stream().anyMatch(d -> d.getArchivedAt() == null)) throw fail("仍有当前有效缺陷，请先核实处理，不会自动清除缺陷");
                    if (checks(p).values().stream().anyMatch(c -> !Set.of("PASS", "NA").contains(c.result()))) throw fail("请逐项完成整备检查，异常或未检查项不能配发");
                    if ("PASS".equals(p.domainCheck) && a.effectiveDomainJoinStatus() != DomainJoinStatus.JOINED)
                        throw fail("请先在资产档案核实并登记已入域；整备不会自动执行加域");
                    p.state = "READY"; p.readyAssetVersion = a.getVersion();
                }
            }
            case "MAINTENANCE", "REQUEST_SCRAP", "SCRAP" -> {
                if (note.isBlank()) throw fail("请填写异常或报废原因");
                if (request.action().equals("SCRAP") && !p.state.equals("RETIREMENT_PENDING")) throw fail("请先登记待报废确认，再由管理员确认报废");
                a.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, request.action().equals("SCRAP") ? "已报废" : "维修中")
                        .orElseThrow(() -> fail("缺少对应资产状态，请管理员先核对基础资料")));
                assets.saveAndFlush(a);
                p.state = request.action().equals("SCRAP") ? "SCRAPPED" : request.action().equals("REQUEST_SCRAP") ? "RETIREMENT_PENDING" : "BLOCKED";
                p.note = note; p.readyAssetVersion = null;
            }
            default -> throw fail("整备操作无效");
        }
        p.updatedAt = LocalDateTime.now();
        String description = actionLabel(request.action()) + (note.isBlank() ? "" : "：" + note);
        if (Set.of("SAVE", "READY").contains(request.action())) description += "；" + checks(p);
        event(p, request.action(), description);
        preparations.saveAndFlush(p);
        audit.success(AuditAction.ASSET_PREPARATION, "ASSET", a.getId(), a.getAssetTag(), description, Map.of("整备状态", p.state, "整备轮次", p.cycle));
        return detail(a, p);
    }

    /** Internal return hook: joins the caller's transaction, so failed returns never leave orphan checklists. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordReturn(Asset asset, String previousHolder) {
        var p = preparations.findByAssetId(asset.getId()).orElse(null);
        if (p == null) { p = new AssetPreparation(); p.asset = asset; } else p.cycle++;
        p.state = "PENDING"; p.note = ""; p.previousHolder = previousHolder; p.readyAssetVersion = null;
        p.dataCheck = p.systemCheck = p.domainCheck = p.hardwareCheck = "UNCHECKED";
        p.dataNote = p.systemNote = p.domainNote = p.hardwareNote = "";
        p.updatedAt = LocalDateTime.now(); event(p, "RETURN", "设备已归还，生成新一轮待整备清单；原领用人：" + Objects.toString(previousHolder, "未记录"));
        preparations.saveAndFlush(p);
    }

    private Overview overview(Asset a, AssetPreparation p) {
        String state = p == null ? "UNVERIFIED" : p.state;
        String reason = p == null ? "尚无整备检查记录" : p.note;
        if (a.getStatus().getName().contains("报废")) { state = "SCRAPPED"; reason = "已报废，不能配发"; }
        else if (a.isCheckedOut()) { state = "CHECKED_OUT"; reason = "设备仍在领用中"; }
        else if (a.getAssignedUserId() != null || !clean(a.getAssignedTo()).isBlank()) { state = "BLOCKED"; reason = "领用信息尚未清空，请核实实际归还情况"; }
        else if (p != null && p.state.equals("RETIREMENT_PENDING")) { state = p.state; }
        else if (a.isInMaintenanceOrScrapped()) { state = "BLOCKED"; reason = reason.isBlank() ? "设备处于维护中" : reason; }
        else if (a.getKnownDefects().stream().anyMatch(d -> d.getArchivedAt() == null)) { state = "BLOCKED"; reason = "存在当前有效缺陷，请核实处理"; }
        else if (p != null && state.equals("READY") && !Objects.equals(p.readyAssetVersion, a.getVersion())) {
            state = "UNVERIFIED"; reason = "资产信息已变化，请重新核验";
        } else if (p != null && reason.isBlank()) reason = switch (state) {
            case "READY" -> "检查完成，可配发"; case "PENDING" -> "归还后尚未整备";
            case "BLOCKED" -> "检查存在异常，请查看整备记录";
            default -> "整备检查尚未完成";
        };
        return new Overview(a.getId(), p == null ? -1 : p.version, a.getVersion(), state, reason,
                p == null ? null : p.previousHolder, p == null ? null : p.updatedAt);
    }
    private Detail detail(Asset a, AssetPreparation p) {
        return new Detail(overview(a, p), p == null ? "" : p.note, p == null ? Map.of() : checks(p), p == null ? List.of() :
                p.events.stream().map(e -> new Event(e.cycle, e.actor, e.action, e.description, e.occurredAt)).toList());
    }
    private Map<String, Check> checks(AssetPreparation p) {
        var map = new LinkedHashMap<String, Check>();
        map.put("DATA", new Check(p.dataCheck, p.dataNote)); map.put("SYSTEM", new Check(p.systemCheck, p.systemNote));
        map.put("DOMAIN", new Check(p.domainCheck, p.domainNote)); map.put("HARDWARE", new Check(p.hardwareCheck, p.hardwareNote)); return map;
    }
    private void applyChecks(AssetPreparation p, Map<String, Check> checks) {
        if (checks == null || !checks.keySet().equals(Set.of("DATA", "SYSTEM", "DOMAIN", "HARDWARE")) || checks.values().stream().anyMatch(Objects::isNull)) throw fail("请提交完整的四项检查");
        for (var c : checks.values()) if (Set.of("ISSUE", "NA").contains(c.result()) && clean(c.note()).isBlank()) throw fail("异常或不适用必须填写原因");
        p.dataCheck = checks.get("DATA").result(); p.dataNote = clean(checks.get("DATA").note());
        p.systemCheck = checks.get("SYSTEM").result(); p.systemNote = clean(checks.get("SYSTEM").note());
        p.domainCheck = checks.get("DOMAIN").result(); p.domainNote = clean(checks.get("DOMAIN").note());
        p.hardwareCheck = checks.get("HARDWARE").result(); p.hardwareNote = clean(checks.get("HARDWARE").note());
    }
    private void event(AssetPreparation p, String action, String description) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        p.events.add(new AssetPreparation.Event(p.cycle, auth == null ? "系统" : auth.getName(), action, description));
    }
    private String actionLabel(String action) { return switch(action) {
        case "START" -> "开始整备"; case "SAVE" -> "保存检查"; case "READY" -> "确认可配发";
        case "MAINTENANCE" -> "转维护中"; case "REQUEST_SCRAP" -> "待报废确认"; case "SCRAP" -> "确认报废（保留档案，不代表实物已处理）"; default -> action;
    }; }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private ApiException fail(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
}
