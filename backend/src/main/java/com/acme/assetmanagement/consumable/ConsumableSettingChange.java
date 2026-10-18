package com.acme.assetmanagement.consumable;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Metadata history is separate from stock movements: changing a unit never changes a past ledger entry. */
@Entity
@Table(name = "consumable_setting_changes")
class ConsumableSettingChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false, unique = true, length = 36, updatable = false) String requestKey;
    @Column(nullable = false, length = 64, updatable = false) String requestHash;
    @Column(nullable = false, updatable = false) Long consumableId;
    @Column(nullable = false, updatable = false) Long actorId;
    @Column(nullable = false, length = 120, updatable = false) String name;
    @Column(nullable = false, length = 20, updatable = false) String unit;
    @Column(nullable = false, updatable = false) long lowStockThreshold;
    @Column(nullable = false, length = 1000, updatable = false) String notes;
    @Column(nullable = false, updatable = false) long itemVersion;
    @Column(nullable = false, updatable = false) LocalDateTime createdAt;
    @PrePersist void create() { createdAt = LocalDateTime.now(); }
}
