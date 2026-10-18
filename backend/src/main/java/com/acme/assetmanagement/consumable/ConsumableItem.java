package com.acme.assetmanagement.consumable;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "consumable_items")
class ConsumableItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true, length = 36, updatable = false) String requestKey;
    @Column(nullable = false, updatable = false) Long creatorId;
    @Column(nullable = false, length = 64, updatable = false) String requestHash;
    @Column(nullable = false, length = 120) String name;
    @Column(nullable = false, length = 20) String unit;
    @Column(nullable = false) long quantity;
    @Column(nullable = false) long lowStockThreshold;
    @Column(nullable = false, length = 1000) String notes;
    @Column(nullable = false, updatable = false) LocalDateTime createdAt;
    @Column(nullable = false) LocalDateTime updatedAt;
    @PrePersist void create() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void update() { updatedAt = LocalDateTime.now(); }
}
