package com.acme.assetmanagement.management;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.operations.OperationService;
import com.acme.assetmanagement.user.*;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @Transactional(readOnly = true)
public class AssetHistoryService {
    private final EntityManager em;
    private final AssetRepository assets;
    private final AssetAccess scope;
    private final ManagementAccess access;
    public AssetHistoryService(EntityManager em, AssetRepository assets, AssetAccess scope, ManagementAccess access) { this.em = em; this.assets = assets; this.scope = scope; this.access = access; }
    public record Event(String kind, Long referenceId, LocalDateTime occurredAt, String actor, String description) {}
    public record History(Long assetId, String assetTag, List<Event> events, String notice) {}
    public History get(Long assetId) {
        var actor = access.current(); if (!actor.effectivePermissions().contains(Permission.ASSET_VIEW)) throw new ApiException(HttpStatus.FORBIDDEN, "没有查看资产权限");
        var a = assets.findById(assetId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "资产不存在")); scope.require(a);
        boolean manager = OperationService.canManage(actor); var events = new ArrayList<Event>();
        events.add(new Event("ASSET", a.getId(), a.getCreatedAt(), "系统台账", "创建资产记录；仅展示已保存且当前有权查看的历史"));
        add(events, "OPERATION", "select e.occurredAt, e.actor, e.description, t.id from OperationTask t join t.events e where t.assetId = :asset" + (manager ? "" : " and (t.ownerId = :uid or t.targetId = :uid)") + " order by e.occurredAt desc", assetId, actor.getId(), !manager);
        add(events, "REGISTRATION", "select e.occurredAt, e.actorName, e.description, t.id from AssetRegistration t join t.events e where t.approvedAssetId = :asset" + (manager ? "" : " and t.ownerId = :uid") + " order by e.occurredAt desc", assetId, actor.getId(), !manager);
        var handovers = em.createQuery("select distinct t.createdAt, t.createdByName, t.kind, t.id from HandoverTask t join t.items i where i.assetId = :asset" + (manager ? "" : " and t.employeeUserId = :uid") + " order by t.createdAt desc", Object[].class).setParameter("asset", assetId).setMaxResults(100);
        if (!manager) handovers.setParameter("uid", actor.getId());
        for (var row : handovers.getResultList()) events.add(new Event("HANDOVER", (Long) row[3], (LocalDateTime) row[0], (String) row[1], "列入" + ("ONBOARDING".equals(row[2]) ? "入职配发" : "离职交接") + "清单；交接本身不修改台账"));
        if (manager) {
            add(events, "WARRANTY", "select e.occurredAt, e.actor, e.description, w.id from AssetWarranty w join w.events e where w.assetId = :asset order by e.occurredAt desc", assetId, actor.getId(), false);
            add(events, "PROCUREMENT", "select e.occurredAt, e.actor, e.description, o.id from ProcurementOrder o join o.events e where :asset member of o.receivedAssetIds order by e.occurredAt desc", assetId, actor.getId(), false);
            var inventory = em.createQuery("select b.updatedAt, i.resolution, b.id from InventoryBatch b join b.items i where i.assetId = :asset and i.resolved = true order by b.updatedAt desc", Object[].class).setParameter("asset", assetId).setMaxResults(100);
            for (var row : inventory.getResultList()) events.add(new Event("INVENTORY", (Long) row[2], (LocalDateTime) row[0], "盘点记录", "管理员核实结果：" + row[1] + "（当前快照，时间为批次最近更新时间，不是逐项验收时间）"));
        }
        if (actor.getRole() == UserRole.SUPER_ADMIN) add(events, "AUDIT", "select l.occurredAt, l.actorUsername, l.summary, l.id from AuditLog l where l.targetType = 'ASSET' and l.targetId = :asset order by l.occurredAt desc", assetId, actor.getId(), false);
        events.sort(Comparator.comparing(Event::occurredAt).reversed());
        return new History(a.getId(), a.getAssetTag(), events, "每类最多最近100条；没有记录的历史无法推断。普通员工只展示本人参与流程，完整操作审计仍仅超级管理员可见。");
    }
    private void add(List<Event> events, String kind, String jpql, Long assetId, Long uid, boolean own) {
        var query = em.createQuery(jpql, Object[].class).setParameter("asset", assetId).setMaxResults(100); if (own) query.setParameter("uid", uid);
        for (var r : query.getResultList()) events.add(new Event(kind, (Long) r[3], (LocalDateTime) r[0], Objects.toString(r[1], ""), Objects.toString(r[2], "")));
    }
}
