package com.acme.assetmanagement.operations;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "asset_operation_tasks", indexes = {
    @Index(name = "idx_operation_owner", columnList = "ownerId,state"),
    @Index(name = "idx_operation_target", columnList = "targetId,state"),
    @Index(name = "idx_operation_oa_pending", columnList = "oaState,oaCheckedAt")})
class OperationTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true, length = 36) String requestKey;
    @Column(nullable = false, length = 20) String kind;
    @Column(nullable = false, length = 24) String state = "PENDING";
    @Column(nullable = false) Long ownerId;
    @Column(nullable = false, length = 80) String ownerName;
    Long targetId;
    @Column(length = 80) String targetName;
    Long assetId;
    // A nullable unique reservation protects accepted workflows without editing the asset ledger.
    @Column(unique = true) Long reservedAssetId;
    Long assetVersion;
    Long handoverTaskId;
    Boolean handoverLegacyReturn;
    @Column(length = 80) String assetTag;
    // Nullable fields leave all existing, manually processed workflows unchanged.
    @Column(length = 24) String oaState;
    @Column(unique = true, length = 160) String oaInstanceId;
    @Column(length = 160) String oaProcessCode;
    @Column(length = 160) String oaCorpId;
    @Column(length = 160) String oaUserId;
    @Column(length = 64) String oaIdentity;
    @Lob String oaFormValue;
    @Column(length = 300) String oaError;
    LocalDateTime oaCheckedAt;
    Boolean oaDirty;
    @Column(nullable = false, length = 160) String title;
    @Column(nullable = false, length = 2000) String description;
    @Column(length = 20) String outcome;
    @Column(precision = 12, scale = 2) BigDecimal repairCost;
    @Column(nullable = false) LocalDateTime createdAt = LocalDateTime.now();
    @Column(nullable = false) LocalDateTime updatedAt = LocalDateTime.now();
    @ElementCollection
    @CollectionTable(name = "asset_operation_proposals", joinColumns = @JoinColumn(name = "task_id"))
    @MapKeyColumn(name = "field_name", length = 40)
    @Column(name = "proposed_value", length = 200)
    java.util.Map<String, String> proposed = new java.util.LinkedHashMap<>();
    @ElementCollection
    @CollectionTable(name = "asset_operation_events", joinColumns = @JoinColumn(name = "task_id"))
    @OrderColumn(name = "event_order")
    List<Event> events = new ArrayList<>();
    @Embeddable public static class Event {
        LocalDateTime occurredAt;
        @Column(length = 80) String actor;
        @Column(length = 2000) String description;
        protected Event() {}
        Event(String actor, String description) { this.occurredAt = LocalDateTime.now(); this.actor = actor; this.description = description; }
    }
}
