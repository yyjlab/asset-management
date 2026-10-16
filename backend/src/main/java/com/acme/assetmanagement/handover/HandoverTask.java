package com.acme.assetmanagement.handover;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Independent handover snapshots. There are deliberately no associations to mutable asset/user rows. */
@Entity
@Table(name = "asset_handover_tasks", indexes = @Index(name = "idx_handover_employee", columnList = "employeeUserId"))
public class HandoverTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true, length = 36) String requestKey;
    @Column(nullable = false, length = 20) String kind;
    @Column(nullable = false, length = 20) String state = "PENDING";
    Long employeeUserId;
    @Column(nullable = false, length = 80) String employeeName;
    @Column(length = 120) String department;
    LocalDate plannedDate;
    @Column(length = 160) String approvalReference;
    @Column(length = 2000) String notes;
    @Column(nullable = false) Long createdById;
    @Column(nullable = false, length = 80) String createdByName;
    @Column(nullable = false, updatable = false) LocalDateTime createdAt;
    @Column(nullable = false) LocalDateTime updatedAt;

    @ElementCollection @BatchSize(size = 50)
    @CollectionTable(name = "asset_handover_items", joinColumns = @JoinColumn(name = "task_id"))
    @OrderColumn(name = "item_order")
    List<Item> items = new ArrayList<>();

    @ElementCollection @BatchSize(size = 50)
    @CollectionTable(name = "asset_handover_events", joinColumns = @JoinColumn(name = "task_id"))
    @OrderColumn(name = "event_order")
    List<Event> events = new ArrayList<>();

    protected HandoverTask() {}

    @PrePersist void timestamps() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }

    @Embeddable
    public static class Item {
        Long assetId;
        @Column(length = 80) String assetTag;
        @Column(nullable = false, length = 160) String name;
        @Column(length = 200) String model;
        @Column(length = 160) String serialNumber;
        @Column(length = 4000) String accessories;
        boolean checked;
        LocalDateTime checkedAt;
        @Column(length = 80) String checkedBy;
        @Column(length = 20) String confirmationMethod;
        LocalDateTime confirmedAt;
        @Column(length = 80) String confirmedBy;
        @Column(length = 1000) String confirmationNote;
        protected Item() {}
    }

    @Embeddable
    public static class Event {
        @Column(nullable = false) LocalDateTime occurredAt;
        @Column(nullable = false) Long actorId;
        @Column(nullable = false, length = 80) String actorName;
        @Column(nullable = false, length = 2000) String description;
        protected Event() {}
    }
}
