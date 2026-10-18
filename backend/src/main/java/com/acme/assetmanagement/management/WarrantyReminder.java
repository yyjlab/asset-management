package com.acme.assetmanagement.management;

import com.acme.assetmanagement.workflow.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.workflow-scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class WarrantyReminder {
    private final EntityManager em;
    private final TaskNotifications notifications;
    private final WorkflowNotificationProperties properties;
    public WarrantyReminder(EntityManager em, TaskNotifications notifications, WorkflowNotificationProperties properties) { this.em = em; this.notifications = notifications; this.properties = properties; }
    @Scheduled(cron = "0 15 9 * * *", zone = "Asia/Shanghai") @Transactional
    public void daily() {
        if (!properties.enabled()) return;
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String where = "from AssetWarranty w where w.warrantyEnd between :today and :end and exists (select a.id from Asset a where a.id = w.assetId)";
        long count = em.createQuery("select count(w) " + where, Long.class).setParameter("today", today).setParameter("end", today.plusDays(30)).getSingleResult();
        if (count == 0) return;
        var rows = em.createQuery("select w.assetTag, w.warrantyEnd " + where + " order by w.warrantyEnd, w.id", Object[].class).setParameter("today", today).setParameter("end", today.plusDays(30)).setMaxResults(10).getResultList();
        var content = new StringBuilder("未来30天保修到期：").append(count).append("台。\n");
        rows.forEach(r -> content.append(r[0]).append("：").append(r[1]).append('\n'));
        content.append("最多列10台，请进入保修与供应商核对售后；不包含SN、价格和联系人。");
        notifications.enqueue("warranty-daily:" + today, "WARRANTY", "资产保修到期提醒", content.toString());
    }
}
