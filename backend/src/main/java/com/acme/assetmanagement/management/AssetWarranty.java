package com.acme.assetmanagement.management;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity @Table(name = "asset_warranties", indexes = @Index(name = "idx_warranty_end", columnList = "warrantyEnd"))
class AssetWarranty {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true) Long assetId;
    @Column(nullable = false, length = 80) String assetTag;
    Long supplierId;
    @Column(length = 120) String supplierName;
    LocalDate purchaseDate;
    @Column(nullable = false) LocalDate warrantyEnd;
    @Column(length = 1000) String notes;
    @Column(nullable = false) LocalDateTime updatedAt = LocalDateTime.now();
    @ElementCollection @CollectionTable(name = "asset_warranty_events", joinColumns = @JoinColumn(name = "warranty_id"))
    @OrderColumn(name = "event_order") List<Event> events = new ArrayList<>();
    @Embeddable public static class Event {
        LocalDateTime occurredAt;
        @Column(length = 80) String actor;
        @Column(length = 2000) String description;
        protected Event() {}
        Event(String actor, String description) { occurredAt = LocalDateTime.now(); this.actor = actor; this.description = description; }
    }
}
