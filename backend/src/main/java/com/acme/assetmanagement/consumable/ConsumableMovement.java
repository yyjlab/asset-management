package com.acme.assetmanagement.consumable;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "consumable_movements", indexes = {
    @Index(name = "idx_consumable_movement_item", columnList = "consumableId,id"),
    @Index(name = "idx_consumable_movement_recipient", columnList = "recipientId,id")
})
class ConsumableMovement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false, unique = true, length = 36, updatable = false) String requestKey;
    @Column(nullable = false, length = 64, updatable = false) String requestHash;
    @Column(nullable = false, updatable = false) Long consumableId;
    @Column(nullable = false, length = 120, updatable = false) String consumableName;
    @Column(nullable = false, length = 20, updatable = false) String unit;
    @Column(nullable = false, length = 3, updatable = false) String kind;
    @Column(nullable = false, updatable = false) long quantity;
    @Column(nullable = false, updatable = false) long balanceAfter;
    @Column(updatable = false) Long recipientId;
    @Column(length = 80, updatable = false) String recipientName;
    @Column(nullable = false, updatable = false) Long actorId;
    @Column(nullable = false, length = 80, updatable = false) String actorName;
    @Column(nullable = false, length = 1000, updatable = false) String note;
    @Column(nullable = false, updatable = false) LocalDateTime createdAt;
    @PrePersist void create() { createdAt = LocalDateTime.now(); }
}
