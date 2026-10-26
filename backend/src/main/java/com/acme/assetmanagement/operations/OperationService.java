package com.acme.assetmanagement.operations;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.user.*;
import com.acme.assetmanagement.workflow.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class OperationService {
    private final OperationRepository tasks;
    private final AssetRepository assets;
    private final UserRepository users;
    private final LookupRepository lookups;
    private final WorkflowAccess access;
    private final TaskNotifications notifications;
    private final AuditLogService audit;
    public OperationService(OperationRepository tasks, AssetRepository assets, UserRepository users,
            LookupRepository lookups, WorkflowAccess access, TaskNotifications notifications, AuditLogService audit) {
        this.tasks = tasks; this.assets = assets; this.users = users; this.lookups = lookups;
        this.access = access; this.notifications = notifications; this.audit = audit;
    }
    public static boolean canManage(UserAccount user) {
        return user.getDataScope() == DataScope.ALL && user.effectivePermissions().containsAll(
                Set.of(Permission.ASSET_VIEW, Permission.ASSET_EDIT, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN));
    }
    private UserAccount manager() {
        var user = access.current();
        if (!canManage(user)) throw fail(HttpStatus.FORBIDDEN, "需要全部资产范围及查看、编辑、分配领用人、归还权限");
        return user;
    }
    public record Summary(Long id, long version, String kind, String state, Long ownerId, String ownerName,
            Long targetId, String targetName, Long assetId, String assetTag, String title, LocalDateTime updatedAt, Long handoverId) {}
    public record Page(List<Summary> items, int page, long totalElements, int totalPages, boolean canManage) {}
    public record Event(LocalDateTime occurredAt, String actor, String description) {}
    public record Detail(Summary task, Long assetVersion, String description, Map<String, String> proposed,
            String outcome, java.math.BigDecimal repairCost, List<Event> events) {}
    public Page list(int page, String kind, boolean mine) {
        var actor = access.current(); boolean manage = canManage(actor);
        if (!kind.isEmpty() && !Set.of("ISSUE", "RETURN", "TRANSFER", "CORRECTION", "REPAIR").contains(kind))
            throw fail(HttpStatus.BAD_REQUEST, "工单类型无效");
        Specification<OperationTask> spec = (r, q, cb) -> manage && !mine ? cb.conjunction()
                : cb.or(cb.equal(r.get("ownerId"), actor.getId()), cb.equal(r.get("targetId"), actor.getId()));
        if (!kind.isEmpty()) spec = spec.and((r, q, cb) -> cb.equal(r.get("kind"), kind));
        var result = tasks.findAll(spec, PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "id")));
        return new Page(result.map(this::summary).getContent(), result.getNumber(), result.getTotalElements(), result.getTotalPages(), manage);
    }
    public Detail get(Long id) { return detail(visible(id, false)); }
    @Transactional
    public Detail create(OperationRequest.Create request) {
        var actor = access.current();
        try { UUID.fromString(request.requestKey()); } catch (IllegalArgumentException e) { throw fail(HttpStatus.BAD_REQUEST, "请求标识无效"); }
        // Serialize duplicate submission and rate-limit checks for this account; no account fields are changed.
        users.findForWorkflowUpdate(actor.getId()).orElseThrow();
        var replay = tasks.findByRequestKey(request.requestKey());
        if (replay.isPresent()) {
            if (!replay.get().ownerId.equals(actor.getId()) && !canManage(actor)) throw fail(HttpStatus.NOT_FOUND, "工单不存在");
            return detail(replay.get());
        }
        if (tasks.countByOwnerIdAndCreatedAtAfter(actor.getId(), LocalDateTime.now().minusDays(1)) >= 30)
            throw fail(HttpStatus.TOO_MANY_REQUESTS, "今日申请较多，请联系管理员");
        var owner = actor;
        if (request.ownerId() != null && !request.ownerId().equals(actor.getId())) { manager(); owner = enabled(request.ownerId()); }
        var t = new OperationTask(); t.requestKey = request.requestKey(); t.kind = request.kind();
        t.ownerId = owner.getId(); t.ownerName = owner.getDisplayName(); t.title = request.title().trim(); t.description = request.description().trim();
        if (request.assetId() != null) {
            var asset = asset(request.assetId());
            if (!Objects.equals(asset.getAssignedUserId(), owner.getId()))
                throw fail(HttpStatus.NOT_FOUND, "只能为明确关联本账号的资产申请归还、转交、纠错或报修");
            snapshot(t, asset);
        }
        if (!"ISSUE".equals(t.kind) && t.assetId == null) throw fail(HttpStatus.BAD_REQUEST, "请选择本人设备");
        if ("ISSUE".equals(t.kind) && t.assetId != null) throw fail(HttpStatus.BAD_REQUEST, "领用申请由管理员选择空闲设备");
        if (Set.of("RETURN", "TRANSFER").contains(t.kind) && !asset(t.assetId).isCheckedOut()) throw fail(HttpStatus.CONFLICT, "资产当前未领出，请核对台账");
        if ("TRANSFER".equals(t.kind)) {
            var target = users.findByUsernameIgnoreCase(clean(request.targetUsername())).filter(UserAccount::isEnabled)
                    .orElseThrow(() -> fail(HttpStatus.BAD_REQUEST, "接收人账号不可用，请填写系统账号而不是姓名"));
            if (target.getId().equals(t.ownerId)) throw fail(HttpStatus.BAD_REQUEST, "不能转交给自己");
            t.targetId = target.getId(); t.targetName = target.getDisplayName();
        } else { t.targetId = t.ownerId; t.targetName = t.ownerName; }
        if ("CORRECTION".equals(t.kind)) {
            if (request.proposed() == null || request.proposed().isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "请填写至少一项纠错字段");
            t.proposed.putAll(request.proposed());
            if (t.proposed.getOrDefault("ownershipDepartment", "").length() > 120) throw fail(HttpStatus.BAD_REQUEST, "部门最多120字");
        } else if (request.proposed() != null && !request.proposed().isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "只有纠错申请可以提交字段修改");
        event(t, actor, "提交申请；正式资产未修改"); return save(t);
    }
    @Transactional
    public Detail action(Long id, OperationRequest.Action request) {
        var actor = access.current(); var t = visible(id, true);
        if (t.handoverTaskId != null) throw fail(HttpStatus.CONFLICT, "此工单关联交接单 #" + t.handoverTaskId + "，请在入离职交接中办理，不能重复审批");
        if (t.oaState != null) throw fail(HttpStatus.CONFLICT, "此申请由钉钉 OA 审批，请进入钉钉审批办理，不能在本地重复审批");
        if (t.version != request.version()) throw fail(HttpStatus.CONFLICT, "工单已变化，请刷新后重试");
        if (Set.of("COMPLETED", "REJECTED", "CANCELLED").contains(t.state)) throw fail(HttpStatus.CONFLICT, "已关闭的工单不能再修改");
        String note = clean(request.note());
        switch (request.action()) {
            case "CANCEL" -> {
                if (!t.ownerId.equals(actor.getId()) && !canManage(actor)) throw fail(HttpStatus.FORBIDDEN, "只有申请人或管理员可取消");
                requireNote(note); if (!"PENDING".equals(t.state)) manager(); t.state = "CANCELLED";
            }
            case "REJECT" -> { manager(); requireNote(note); if (!"PENDING".equals(t.state)) throw fail(HttpStatus.CONFLICT, "只能驳回待受理申请"); t.state = "REJECTED"; }
            case "APPROVE" -> {
                manager(); requireState(t, "PENDING");
                Asset a;
                if ("ISSUE".equals(t.kind)) {
                    if (request.assetId() == null || request.assetVersion() == null) throw fail(HttpStatus.BAD_REQUEST, "请选择设备并核对资产版本");
                    a = lockedAsset(request.assetId()); version(a, request.assetVersion()); available(a);
                } else { a = lockedAsset(t.assetId); version(a, t.assetVersion); ownership(t, a); }
                if (tasks.existsByReservedAssetId(a.getId())) throw fail(HttpStatus.CONFLICT, "此设备已有已受理的工单，请先完结或取消原工单，不能重复分配");
                enabled(t.ownerId); enabled(t.targetId);
                if ("ISSUE".equals(t.kind)) snapshot(t, a);
                t.reservedAssetId = a.getId();
                t.state = "REPAIR".equals(t.kind) ? "PROCESSING" : "WAITING_CONFIRM";
            }
            case "PROGRESS" -> { manager(); requireState(t, "PROCESSING"); requireNote(note); }
            case "RESOLVE" -> {
                manager(); requireState(t, "PROCESSING"); requireNote(note);
                if (request.outcome() == null || request.repairCost() == null) throw fail(HttpStatus.BAD_REQUEST, "请填写维修结果及费用（无费用填0）");
                t.outcome = request.outcome(); t.repairCost = request.repairCost(); t.state = "WAITING_CONFIRM";
            }
            case "CONFIRM" -> {
                requireState(t, "WAITING_CONFIRM");
                if (!Objects.equals(t.targetId, actor.getId())) throw fail(HttpStatus.FORBIDDEN, "只能由接收人本人确认，管理员不可代签");
                if (!Boolean.TRUE.equals(request.confirmed())) throw fail(HttpStatus.BAD_REQUEST, "请明确确认实物及处理结果"); t.state = "CONFIRMED";
            }
            case "COMPLETE" -> {
                manager(); requireState(t, "CONFIRMED"); requireNote(note);
                if (!Boolean.TRUE.equals(request.confirmed()) || request.assetVersion() == null) throw fail(HttpStatus.BAD_REQUEST, "请确认将本次结果写入台账并提供资产版本");
                var a = lockedAsset(t.assetId); version(a, t.assetVersion); version(a, request.assetVersion());
                enabled(t.ownerId); enabled(t.targetId);
                if ("ISSUE".equals(t.kind)) { available(a); a.setAssignedUserId(t.ownerId); a.setAssignedTo(enabled(t.ownerId).getDisplayName()); a.setCheckedOut(true); a.setStatus(status("已经领出")); }
                else {
                    ownership(t, a);
                    switch (t.kind) {
                        case "RETURN" -> { a.setCheckedOut(false); a.setAssignedUserId(null); a.setAssignedTo(null); if (!a.isInMaintenanceOrScrapped()) a.setStatus(status("当前可用")); }
                        case "TRANSFER" -> { a.setAssignedUserId(t.targetId); a.setAssignedTo(enabled(t.targetId).getDisplayName()); a.setCheckedOut(true); if (!a.isInMaintenanceOrScrapped()) a.setStatus(status("已经领出")); }
                        case "REPAIR" -> a.setStatus(status("SCRAP".equals(t.outcome) ? "已报废" : a.isCheckedOut() ? "已经领出" : "当前可用"));
                        case "CORRECTION" -> t.proposed.forEach((key, value) -> { switch (key) {
                            case "cpu" -> a.setCpu(value.trim()); case "memory" -> a.setMemory(value.trim());
                            case "storage" -> a.setStorage(value.trim()); case "ownershipDepartment" -> a.setOwnershipDepartment(value.trim());
                            default -> throw fail(HttpStatus.BAD_REQUEST, "不支持此纠错字段");
                        } });
                        default -> throw fail(HttpStatus.BAD_REQUEST, "工单类型无效");
                    }
                }
                assets.saveAndFlush(a); t.state = "COMPLETED";
                audit.success(AuditAction.ASSET_UPDATE, "ASSET", a.getId(), a.getAssetTag(),
                        "明确确认工单 #" + t.id + "（" + t.kind + "）并写入台账", Map.of("工单ID", t.id, "处理说明", note));
            }
            default -> throw fail(HttpStatus.BAD_REQUEST, "操作无效");
        }
        if (Set.of("COMPLETED", "CANCELLED", "REJECTED").contains(t.state)) t.reservedAssetId = null;
        String actionLabel = switch (request.action()) {
            case "APPROVE" -> "管理员受理"; case "PROGRESS" -> "维修进度"; case "RESOLVE" -> "提交维修结果";
            case "CONFIRM" -> "接收人本人确认"; case "COMPLETE" -> "管理员完结并写入台账";
            case "REJECT" -> "驳回申请"; default -> "取消工单";
        };
        event(t, actor, actionLabel + (note.isEmpty() ? "" : "：" + note)); return save(t);
    }
    // Internal only: caller must have verified the official OA instance, employee attestation and IT approval.
    void completeFromOa(OperationTask t, String approver) {
        if (!Set.of("ISSUE", "RETURN").contains(t.kind) || !"AGREED".equals(t.oaState))
            throw fail(HttpStatus.CONFLICT, "OA 办理条件不满足");
        var a = lockedAsset(t.assetId); version(a, t.assetVersion);
        var owner = enabled(t.ownerId);
        if (!Objects.equals(t.oaIdentity, owner.getDingtalkIdentity())) throw fail(HttpStatus.CONFLICT, "员工钉钉绑定已改变，请核对身份后重新申请");
        if ("ISSUE".equals(t.kind)) {
            available(a); a.setAssignedUserId(owner.getId()); a.setAssignedTo(owner.getDisplayName());
            a.setCheckedOut(true); a.setStatus(status("已经领出"));
        } else {
            ownership(t, a);
            if (!a.isCheckedOut()) throw fail(HttpStatus.CONFLICT, "资产当前未领出，拒绝重复归还");
            a.setAssignedUserId(null); a.setAssignedTo(null); a.setCheckedOut(false);
            if (!a.isInMaintenanceOrScrapped()) a.setStatus(status("当前可用"));
        }
        assets.saveAndFlush(a); t.state = "COMPLETED"; t.oaState = "APPLIED"; t.reservedAssetId = null;
        t.events.add(new OperationTask.Event("钉钉 OA", "审批通过并自动写入台账；实物审批人 userid：" + approver));
        t.updatedAt = LocalDateTime.now();
        audit.success(AuditAction.ASSET_UPDATE, "ASSET", a.getId(), a.getAssetTag(),
                "钉钉 OA 审批通过并自动办理" + t.kind,
                Map.of("工单ID", t.id, "审批实例", t.oaInstanceId, "实物审批人", approver));
        save(t);
    }
    private void ownership(OperationTask t, Asset a) { if (!Objects.equals(t.ownerId, a.getAssignedUserId())) throw fail(HttpStatus.CONFLICT, "资产归属已改变，请取消后重新申请；不会覆盖现有领用人"); }
    private void available(Asset a) {
        if (a.isCheckedOut() || a.getAssignedUserId() != null || !clean(a.getAssignedTo()).isEmpty()
                || !Set.of("当前可用", "可领用").contains(a.getStatus().getName())) throw fail(HttpStatus.CONFLICT, "所选设备不是可分配的空闲设备");
    }
    private LookupValue status(String name) {
        String legacy = switch (name) { case "当前可用" -> "可领用"; case "已经领出" -> "在用"; default -> name; };
        return lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, name)
                .or(() -> lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, legacy))
                .orElseThrow(() -> fail(HttpStatus.CONFLICT, "请先在资产状态中配置：" + name));
    }
    private void version(Asset a, Long version) { if (version == null || a.getVersion() != version) throw fail(HttpStatus.CONFLICT, "资产台账已变化，请取消工单并重新核对；不会覆盖其他修改"); }
    private Asset asset(Long id) { return assets.findById(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "资产不存在")); }
    private Asset lockedAsset(Long id) { return assets.findForRegistrationLink(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "资产不存在")); }
    private UserAccount enabled(Long id) { return users.findById(id).filter(UserAccount::isEnabled).orElseThrow(() -> fail(HttpStatus.CONFLICT, "关联账号不可用，请管理员核对")); }
    private OperationTask visible(Long id, boolean lock) {
        var actor = access.current(); var t = (lock ? tasks.locked(id) : tasks.findById(id)).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "工单不存在"));
        if (!canManage(actor) && !Objects.equals(t.ownerId, actor.getId()) && !Objects.equals(t.targetId, actor.getId())) throw fail(HttpStatus.NOT_FOUND, "工单不存在");
        return t;
    }
    private void snapshot(OperationTask t, Asset a) { t.assetId = a.getId(); t.assetTag = a.getAssetTag(); t.assetVersion = a.getVersion(); }
    private void requireState(OperationTask t, String state) { if (!state.equals(t.state)) throw fail(HttpStatus.CONFLICT, "当前工单状态不允许此操作"); }
    private void requireNote(String note) { if (note.isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "请填写处理说明"); }
    private void event(OperationTask t, UserAccount actor, String text) { t.events.add(new OperationTask.Event(actor.getDisplayName(), text)); t.updatedAt = LocalDateTime.now(); }
    private Detail save(OperationTask t) {
        tasks.saveAndFlush(t);
        String stateLabel = switch (t.state) {
            case "PENDING" -> "待管理员受理"; case "PROCESSING" -> "维修处理中"; case "WAITING_CONFIRM" -> "待接收人本人确认";
            case "CONFIRMED" -> "待管理员验收完结"; case "COMPLETED" -> "已完结并写入台账";
            case "REJECTED" -> "已驳回"; default -> "已取消";
        };
        notifications.enqueue("operation:" + t.id + ":" + t.version, "OPERATION", "资产工单进度", "工单 #" + t.id + "\n申请人：" + t.ownerName + "\n状态：" + stateLabel + "\n请进入资产系统查看详情。");
        return detail(t);
    }
    private Summary summary(OperationTask t) { return new Summary(t.id, t.version, t.kind, t.state, t.ownerId, t.ownerName, t.targetId, t.targetName, t.assetId, t.assetTag, t.title, t.updatedAt, t.handoverTaskId); }
    private Detail detail(OperationTask t) { return new Detail(summary(t), t.assetVersion, t.description, Map.copyOf(t.proposed), t.outcome, t.repairCost, t.events.stream().map(e -> new Event(e.occurredAt, e.actor, e.description)).toList()); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static ApiException fail(HttpStatus status, String message) { return new ApiException(status, message); }
}
