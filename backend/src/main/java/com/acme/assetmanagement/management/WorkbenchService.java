package com.acme.assetmanagement.management;

import com.acme.assetmanagement.operations.OperationService;
import com.acme.assetmanagement.user.*;
import com.acme.assetmanagement.workflow.WorkflowAccess;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @Transactional(readOnly = true)
public class WorkbenchService {
    private final EntityManager em;
    private final WorkflowAccess access;
    public WorkbenchService(EntityManager em, WorkflowAccess access) { this.em = em; this.access = access; }
    public record Item(Long id, String title, String state, LocalDateTime updatedAt) {}
    public record Group(String section, String title, long total, List<Item> items) {}
    public record Overview(long total, List<Group> groups) {}
    public Overview get() {
        var user = access.current(); boolean manager = OperationService.canManage(user); var groups = new ArrayList<Group>(); var own = Map.<String, Object>of("uid", user.getId());
        String operations = "from OperationTask t where " + (manager ? "t.state in ('PENDING','PROCESSING','CONFIRMED') or (t.state = 'WAITING_CONFIRM' and t.targetId = :uid)" : "t.state = 'WAITING_CONFIRM' and t.targetId = :uid");
        groups.add(group("operations", "领用、归还、维修待办", "t.title", "t.state", operations, own));
        boolean review = access.canReview(user);
        groups.add(group("registrations", "设备登记待办", "t.name", "t.state", "from AssetRegistration t where " + (review ? "t.state = 'SUBMITTED' or (t.ownerId = :uid and t.state = 'NEEDS_INFO')" : "t.ownerId = :uid and t.state = 'NEEDS_INFO'"), own));
        boolean handoverManager = user.getDataScope() == DataScope.ALL && user.effectivePermissions().containsAll(Set.of(Permission.ASSET_VIEW, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN));
        String handover = "from HandoverTask t where t.state in ('PENDING','IN_PROGRESS')" + (handoverManager ? "" : " and t.employeeUserId = :uid and exists (select h.id from HandoverTask h join h.items i where h.id = t.id and i.checked = true and i.confirmedAt is null)");
        groups.add(group("handover", handoverManager ? "交接待跟进" : "交接待本人确认", "t.employeeName", "t.state", handover, handoverManager ? Map.of() : own));
        String inventory = "from InventoryBatch t where t.state = 'OPEN'" + (manager ? "" : " and exists (select b.id from InventoryBatch b join b.items i where b.id = t.id and i.ownerId = :uid and i.result is null and i.resolved = false)");
        groups.add(group("inventory", "盘点待办", "t.title", "t.state", inventory, manager ? Map.of() : own));
        if (manager) {
            groups.add(group("procurement", "采购待处理", "t.title", "t.state", "from ProcurementOrder t where t.state in ('SUBMITTED','APPROVED','ORDERED')", Map.of()));
            groups.add(group("consumables", "耗材库存预警", "t.name", "'LOW_STOCK'", "from ConsumableItem t where t.quantity <= t.lowStockThreshold", Map.of()));
            var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            groups.add(group("support", "30天内保修到期", "t.assetTag", "'WARRANTY_DUE'", "from AssetWarranty t where t.warrantyEnd between :today and :end and exists (select a.id from Asset a where a.id = t.assetId)", Map.of("today", today, "end", today.plusDays(30))));
        }
        return new Overview(groups.stream().mapToLong(Group::total).sum(), groups);
    }
    private Group group(String section, String title, String field, String state, String from, Map<String, Object> params) {
        var count = em.createQuery("select count(t) " + from, Long.class); params.forEach(count::setParameter);
        // Warranty links address asset IDs; other groups address their own record IDs.
        String id = "support".equals(section) ? "t.assetId" : "t.id";
        var query = em.createQuery("select " + id + ", " + field + ", " + state + ", t.updatedAt " + from + " order by t.updatedAt desc, t.id desc", Object[].class).setMaxResults(10); params.forEach(query::setParameter);
        return new Group(section, title, count.getSingleResult(), query.getResultList().stream().map(row -> new Item((Long) row[0], (String) row[1], (String) row[2], (LocalDateTime) row[3])).toList());
    }
}
