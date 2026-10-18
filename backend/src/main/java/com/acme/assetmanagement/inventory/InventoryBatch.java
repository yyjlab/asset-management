package com.acme.assetmanagement.inventory;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "asset_inventory_batches")
class InventoryBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true, length = 36) String requestKey;
    @Column(nullable = false, length = 160) String title;
    @Column(nullable = false, length = 120) String department;
    @Column(nullable = false) LocalDate dueDate;
    @Column(nullable = false, length = 20) String state = "OPEN";
    @Column(nullable = false) LocalDateTime createdAt = LocalDateTime.now();
    @Column(nullable = false) LocalDateTime updatedAt = LocalDateTime.now();
    @ElementCollection
    @CollectionTable(name = "asset_inventory_items", joinColumns = @JoinColumn(name = "batch_id"), indexes = @Index(name = "idx_inventory_owner", columnList = "ownerId"))
    @OrderColumn(name = "item_order")
    List<Item> items = new ArrayList<>();
    @ElementCollection
    @CollectionTable(name = "asset_inventory_events", joinColumns = @JoinColumn(name = "batch_id"))
    @OrderColumn(name = "event_order")
    List<Event> events = new ArrayList<>();
    @Embeddable public static class Item {
        Long assetId;
        long assetVersion;
        @Column(length = 80) String assetTag;
        @Column(length = 160) String name;
        Long ownerId;
        @Column(length = 120) String ownerName;
        @Column(length = 20) String result;
        @Column(length = 1000) String employeeNote;
        LocalDateTime confirmedAt;
        boolean resolved;
        @Column(length = 1000) String resolution;
    }
    @Embeddable public static class Event {
        LocalDateTime occurredAt;
        @Column(length = 80) String actor;
        @Column(length = 2000) String description;
        protected Event() {}
        Event(String actor, String text) { occurredAt = LocalDateTime.now(); this.actor = actor; description = text; }
    }
}
