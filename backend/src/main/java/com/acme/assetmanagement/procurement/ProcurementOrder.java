package com.acme.assetmanagement.procurement;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Entity @Table(name = "asset_procurement_orders", indexes = @Index(name = "idx_procurement_owner", columnList = "ownerId,state"))
class ProcurementOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true, length = 36) String requestKey;
    @Column(nullable = false) Long ownerId;
    @Column(nullable = false, length = 80) String ownerName;
    @Column(nullable = false, length = 20) String kind;
    @Column(nullable = false, length = 20) String state = "SUBMITTED";
    @Column(nullable = false, length = 160) String title;
    @Column(nullable = false, length = 2000) String reason;
    @Column(nullable = false) int quantity;
    @Column(nullable = false, length = 20) String unit;
    @Column(nullable = false, precision = 12, scale = 2) BigDecimal estimatedUnitPrice;
    @Column(precision = 12, scale = 2) BigDecimal orderedUnitPrice;
    LocalDate neededDate;
    LocalDate expectedDate;
    Long supplierId;
    @Column(length = 120) String supplierName;
    @Column(length = 120) String orderNumber;
    Long consumableId;
    Long receiptMovementId;
    @Column(nullable = false, updatable = false) LocalDateTime createdAt = LocalDateTime.now();
    @Column(nullable = false) LocalDateTime updatedAt = LocalDateTime.now();
    @ElementCollection @CollectionTable(name = "procurement_receipt_assets", joinColumns = @JoinColumn(name = "order_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_procurement_receipt_asset", columnNames = "asset_id"))
    @Column(name = "asset_id", nullable = false) Set<Long> receivedAssetIds = new LinkedHashSet<>();
    @ElementCollection @CollectionTable(name = "asset_procurement_events", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "event_order") List<Event> events = new ArrayList<>();
    @Embeddable public static class Event {
        LocalDateTime occurredAt;
        @Column(length = 80) String actor;
        @Column(length = 2000) String description;
        protected Event() {}
        Event(String actor, String description) { occurredAt = LocalDateTime.now(); this.actor = actor; this.description = description; }
    }
}
