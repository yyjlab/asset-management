package com.acme.assetmanagement.operations;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.user.*;
import com.acme.assetmanagement.workflow.WorkflowAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** Internal bridge. Always joins the handover transaction; it has no HTTP endpoints. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class HandoverLedger {
    private final OperationRepository operations;
    private final AssetRepository assets;
    private final UserRepository users;
    private final LookupRepository lookups;
    private final WorkflowAccess access;
    private final AuditLogService audit;
    private final jakarta.persistence.EntityManager entityManager;

    public HandoverLedger(OperationRepository operations, AssetRepository assets, UserRepository users,
            LookupRepository lookups, WorkflowAccess access, AuditLogService audit, jakarta.persistence.EntityManager entityManager) {
        this.operations = operations; this.assets = assets; this.users = users;
        this.lookups = lookups; this.access = access; this.audit = audit; this.entityManager = entityManager;
    }

    public record Reservation(Long operationId, long assetVersion) {}

    public Reservation reserve(Long handoverId, String kind, Long employeeId, String employeeName,
            Long assetId, Long expectedVersion, boolean allowLegacy) {
        var actor = manager();
        var asset = lockedAsset(assetId); version(asset, expectedVersion);
        String operationKind = "ONBOARDING".equals(kind) ? "ISSUE" : "RETURN";
        validateAsset(asset, operationKind, employeeId, allowLegacy);
        if (operations.existsByReservedAssetId(assetId)) throw conflict("设备 " + asset.getAssetTag() + " 已有办理中的交接或工单，请先完结或取消");
        var owner = employeeId == null ? actor : employee(employeeId, "ISSUE".equals(operationKind));
        var operation = new OperationTask();
        operation.requestKey = UUID.randomUUID().toString(); operation.kind = operationKind;
        operation.state = "HANDOVER_PENDING"; operation.handoverTaskId = handoverId;
        operation.handoverLegacyReturn = "RETURN".equals(operationKind) && asset.getAssignedUserId() == null && allowLegacy;
        operation.ownerId = owner.getId(); operation.ownerName = owner.getDisplayName();
        operation.targetId = employeeId; operation.targetName = employeeName;
        operation.assetId = assetId; operation.assetVersion = asset.getVersion(); operation.assetTag = asset.getAssetTag();
        operation.reservedAssetId = assetId;
        operation.title = ("ISSUE".equals(operationKind) ? "入职配发" : "离职归还") + " · 交接单 #" + handoverId;
        operation.description = "关联交接单 #" + handoverId + "；在入离职交接中完成实物验收及签收。";
        operation.events.add(new OperationTask.Event(actor.getDisplayName(), "交接建单并预留设备，台账尚未修改"));
        operations.saveAndFlush(operation);
        return new Reservation(operation.id, asset.getVersion());
    }

    public void linkEmployee(Long operationId, Long handoverId, Long employeeId) {
        var actor = manager(); var operation = pending(operationId, handoverId);
        var owner = employee(employeeId, "ISSUE".equals(operation.kind));
        var asset = lockedAsset(operation.assetId); version(asset, operation.assetVersion);
        validateAsset(asset, operation.kind, employeeId, Boolean.TRUE.equals(operation.handoverLegacyReturn));
        operation.ownerId = owner.getId(); operation.ownerName = owner.getDisplayName();
        operation.targetId = owner.getId(); operation.targetName = owner.getDisplayName();
        operation.events.add(new OperationTask.Event(actor.getDisplayName(), "交接单明确关联员工账号"));
        operation.updatedAt = LocalDateTime.now(); operations.saveAndFlush(operation);
    }

    public void finish(Long operationId, Long handoverId, Long employeeId) {
        var actor = manager(); var operation = pending(operationId, handoverId);
        if (employeeId == null || !Objects.equals(operation.targetId, employeeId) || !Objects.equals(operation.ownerId, employeeId))
            throw conflict("请先明确关联并核对员工账号");
        var owner = employee(employeeId, "ISSUE".equals(operation.kind));
        var asset = lockedAsset(operation.assetId); version(asset, operation.assetVersion);
        validateAsset(asset, operation.kind, employeeId, Boolean.TRUE.equals(operation.handoverLegacyReturn));
        Map<String, Object> before = custody(asset);
        if ("ISSUE".equals(operation.kind)) {
            asset.setAssignedUserId(employeeId); asset.setAssignedTo(owner.getDisplayName());
            asset.setCheckedOut(true); asset.setStatus(status("已经领出", "在用"));
        } else {
            asset.setAssignedUserId(null); asset.setAssignedTo(null); asset.setCheckedOut(false);
            if (!asset.isInMaintenanceOrScrapped()) asset.setStatus(status("当前可用", "可领用"));
        }
        assets.saveAndFlush(asset);
        operation.state = "COMPLETED"; operation.reservedAssetId = null; operation.updatedAt = LocalDateTime.now();
        operation.events.add(new OperationTask.Event(actor.getDisplayName(), "交接单 #" + handoverId + " 完结并写入资产台账"));
        operations.saveAndFlush(operation);
        var changes = new LinkedHashMap<String, Object>(audit.diff(before, custody(asset)));
        changes.put("交接单ID", handoverId); changes.put("领用归还工单ID", operation.id);
        audit.success(AuditAction.ASSET_UPDATE, "ASSET", asset.getId(), asset.getAssetTag(),
                ("ISSUE".equals(operation.kind) ? "入职配发" : "离职归还") + "，交接单 #" + handoverId, changes);
    }

    public void cancel(Long operationId, Long handoverId) {
        var actor = manager(); var operation = pending(operationId, handoverId);
        operation.state = "CANCELLED"; operation.reservedAssetId = null; operation.updatedAt = LocalDateTime.now();
        operation.events.add(new OperationTask.Event(actor.getDisplayName(), "交接单取消，释放预留，未修改台账"));
        operations.saveAndFlush(operation);
    }

    private OperationTask pending(Long id, Long handoverId) {
        if (id == null) throw conflict("交接设备缺少关联工单，请取消后重新建立交接单");
        var operation = operations.locked(id).orElseThrow(() -> conflict("关联工单不存在"));
        if (!Objects.equals(handoverId, operation.handoverTaskId) || !"HANDOVER_PENDING".equals(operation.state)
                || !Objects.equals(operation.assetId, operation.reservedAssetId)) throw conflict("关联工单状态已改变，请刷新核对");
        return operation;
    }
    private UserAccount manager() {
        var actor = access.current();
        if (actor.getDataScope() != DataScope.ALL || !actor.effectivePermissions().containsAll(
                Set.of(Permission.ASSET_VIEW, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN)))
            throw new ApiException(HttpStatus.FORBIDDEN, "需要全部资产范围及查看、分配领用人、归还权限");
        return actor;
    }
    private Asset lockedAsset(Long id) {
        var asset = assets.findForRegistrationLink(id).orElseThrow(() -> conflict("交接设备已不存在"));
        entityManager.refresh(asset, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return asset;
    }
    private UserAccount employee(Long id, boolean enabled) {
        var user = users.findById(id).orElseThrow(() -> conflict("关联员工账号已不存在"));
        if (enabled && !user.isEnabled()) throw conflict("员工账号已停用，不能配发设备");
        return user;
    }
    private void version(Asset asset, Long expected) {
        if (expected == null || asset.getVersion() != expected) throw conflict("设备 " + asset.getAssetTag() + " 已变化，请取消交接单并重新核对，不会覆盖其他修改");
    }
    private void validateAsset(Asset asset, String kind, Long employeeId, boolean allowLegacy) {
        if ("ISSUE".equals(kind)) {
            if (asset.isCheckedOut() || asset.getAssignedUserId() != null || asset.getAssignedTo() != null && !asset.getAssignedTo().isBlank()
                    || !Set.of("当前可用", "可领用").contains(asset.getStatus().getName())) throw conflict("设备 " + asset.getAssetTag() + " 不是可配发的空闲设备");
        } else if (!asset.isCheckedOut() || (asset.getAssignedUserId() == null ? !allowLegacy : !Objects.equals(employeeId, asset.getAssignedUserId()))) {
            throw conflict("设备 " + asset.getAssetTag() + " 未领出或领用账号不匹配，请核对归属");
        }
    }
    private LookupValue status(String name, String legacy) {
        return lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, name)
                .or(() -> lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, legacy))
                .orElseThrow(() -> conflict("资产状态配置缺少“" + name + "”"));
    }
    private Map<String, Object> custody(Asset asset) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("领用账号ID", asset.getAssignedUserId()); values.put("领用人", asset.getAssignedTo());
        values.put("已领出", asset.isCheckedOut()); values.put("资产状态", asset.getStatus().getName());
        return values;
    }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
}
