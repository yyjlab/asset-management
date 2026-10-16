package com.acme.assetmanagement.workflow;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.workflow-scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class TaskNotificationWorker {
    private final WorkflowNotificationProperties properties;
    private final TaskNotifications notifications;
    private final DingTalkTaskNotifier notifier;
    public TaskNotificationWorker(WorkflowNotificationProperties properties, TaskNotifications notifications, DingTalkTaskNotifier notifier) {
        this.properties = properties; this.notifications = notifications; this.notifier = notifier;
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void deliver() {
        if (!properties.configured()) return;
        for (int index = 0; index < 20; index++) {
            var delivery = notifications.claim();
            if (delivery.isEmpty()) return;
            notifications.finish(delivery.get(), notifier.send(delivery.get()));
        }
    }
}
