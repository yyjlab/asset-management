package com.acme.assetmanagement.management;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service @Transactional(readOnly = true)
public class ManagementReportService {
    private final ManagementAccess access;
    private final EntityManager em;
    public ManagementReportService(ManagementAccess access, EntityManager em) { this.access = access; this.em = em; }
    public record Count(String label, long count) {}
    public record Department(String name, long assets, long checkedOut) {}
    public record Overview(LocalDateTime generatedAt, long assets, long idle, long unlinkedCheckedOut, long lowStock,
            long openDiscrepancies, BigDecimal completedRepairCost, List<Count> statuses, List<Department> departments,
            List<Count> procurements, String notice) {}
    public Overview get() {
        access.manager();
        var departments = em.createQuery("select a.ownershipDepartment, count(a), sum(case when a.checkedOut = true then 1 else 0 end) from Asset a group by a.ownershipDepartment order by count(a) desc", Object[].class)
                .getResultList().stream().map(r -> new Department(Objects.toString(r[0], "未设置"), ((Number) r[1]).longValue(), ((Number) r[2]).longValue())).toList();
        BigDecimal repair = em.createQuery("select coalesce(sum(t.repairCost), 0) from OperationTask t where t.kind = 'REPAIR' and t.state = 'COMPLETED'", BigDecimal.class).getSingleResult();
        return new Overview(LocalDateTime.now(), count("select count(a) from Asset a"), count("select count(a) from Asset a where a.checkedOut = false and a.assignedUserId is null and (a.assignedTo is null or a.assignedTo = '') and a.status.name in ('当前可用','可领用')"),
                count("select count(a) from Asset a where a.checkedOut = true and a.assignedUserId is null"), count("select count(c) from ConsumableItem c where c.quantity <= c.lowStockThreshold"),
                count("select count(i.assetId) from InventoryBatch b join b.items i where b.state = 'OPEN' and i.resolved = false and i.result in ('MISSING','MISMATCH')"), repair,
                counts("select a.status.name, count(a) from Asset a group by a.status.name order by count(a) desc"), departments,
                counts("select p.state, count(p) from ProcurementOrder p group by p.state order by p.state"),
                "资产数量为当前台账快照；维修费仅累计本系统已完结维修工单，不含历史未录入费用，不作为财务报表。所有统计仅对全部资产管理权限开放。");
    }
    private long count(String sql) { return em.createQuery(sql, Long.class).getSingleResult(); }
    private List<Count> counts(String sql) { return em.createQuery(sql, Object[].class).getResultList().stream().map(r -> new Count(Objects.toString(r[0], "未设置"), ((Number) r[1]).longValue())).toList(); }
}
