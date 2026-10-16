package com.acme.assetmanagement.handover;

import com.acme.assetmanagement.workflow.TaskNotifications;
import com.acme.assetmanagement.workflow.WorkflowNotificationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.workflow-scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class HandoverReminder {
    private final HandoverRepository tasks;
    private final TaskNotifications notifications;
    private final WorkflowNotificationProperties properties;
    public HandoverReminder(HandoverRepository tasks, TaskNotifications notifications, WorkflowNotificationProperties properties) {
        this.tasks = tasks; this.notifications = notifications; this.properties = properties;
    }
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void daily() {
        if (!properties.enabled()) return;
        var states = Set.of("PENDING", "IN_PROGRESS");
        long count = tasks.countByStateIn(states);
        if (count == 0) return;
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        long overdue = tasks.countByStateInAndPlannedDateBefore(states, today);
        var content = new StringBuilder("待处理交接：").append(count).append("张；超过计划日期：").append(overdue).append("张。\n");
        for (var task : tasks.findTop10ByStateInOrderByPlannedDateAscIdAsc(states)) {
            content.append('#').append(task.id).append(' ').append(task.employeeName).append("：");
            if (task.items.stream().anyMatch(item -> !item.checked)) content.append("待管理员验收／配发");
            else if (task.items.stream().anyMatch(item -> item.confirmedAt == null)) content.append("待员工确认");
            else content.append("待管理员完结");
            content.append('\n');
        }
        content.append("以上最多列10张，请进入资产系统查看完整待办；不自动修改资产台账。");
        notifications.enqueue("handover-daily:" + today, "REMINDER", "资产交接每日待办", content.toString());
    }
}
