package com.acme.assetmanagement.workflow;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "asset_task_notifications", indexes = @Index(name = "idx_task_notification_due", columnList = "state,nextAttemptAt"))
public class TaskNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true, length = 128) String eventKey;
    @Column(nullable = false, length = 30) String category;
    @Column(nullable = false, length = 100) String title;
    @Column(nullable = false, length = 2000) String content;
    @Column(nullable = false, length = 20) String state = "PENDING";
    int attempts;
    @Column(length = 100) String lastError;
    @Column(length = 36) String leaseId;
    Instant leasedUntil;
    // Persist millisecond precision so DB timestamp rounding cannot move a new job into the future.
    @Column(nullable = false) Instant nextAttemptAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    @Column(nullable = false, updatable = false) Instant createdAt = Instant.now();
    Instant sentAt;
    protected TaskNotification() {}
}
