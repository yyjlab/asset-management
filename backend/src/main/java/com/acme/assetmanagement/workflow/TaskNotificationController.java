package com.acme.assetmanagement.workflow;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/task-notifications")
public class TaskNotificationController {
    private final WorkflowAccess access;
    private final TaskNotifications notifications;
    public TaskNotificationController(WorkflowAccess access, TaskNotifications notifications) { this.access = access; this.notifications = notifications; }
    @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping public TaskNotifications.Status status() { access.administrator(); return notifications.status(); }
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable Long id) { access.administrator(); notifications.retry(id); }
}
