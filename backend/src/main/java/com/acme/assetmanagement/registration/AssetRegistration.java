package com.acme.assetmanagement.registration;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "asset_registration_requests", indexes = {
        @Index(name = "idx_registration_owner", columnList = "ownerId,state"),
        @Index(name = "idx_registration_queue", columnList = "state,createdAt")})
public class AssetRegistration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true, length = 36) String requestKey;
    @Column(nullable = false) Long ownerId;
    @Column(nullable = false, length = 80) String ownerName;
    @Column(nullable = false, length = 20) String state = "SUBMITTED";
    @Column(nullable = false, length = 160) String name;
    @Column(nullable = false, length = 120) String model;
    @Column(length = 160) String serialNumber;
    @Column(length = 64) String serialKey;
    @Column(length = 120) String department;
    @Column(length = 120) String location;
    @Column(length = 200) String cpu;
    @Column(length = 200) String memory;
    @Column(length = 200) String storage;
    @Column(length = 2000) String notes;
    @Column(length = 1000) String reviewNote;
    @Column(length = 80) String reviewerName;
    @Column(unique = true) Long approvedAssetId;
    @Column(length = 80) String approvedAssetTag;
    @Column(unique = true, length = 64) String approvedSerialKey;
    int photoRevision = 1;
    @Column(nullable = false, updatable = false) LocalDateTime createdAt;
    @Column(nullable = false) LocalDateTime updatedAt;
    @ElementCollection @BatchSize(size = 30)
    @CollectionTable(name = "asset_registration_events", joinColumns = @JoinColumn(name = "registration_id"))
    @OrderColumn(name = "event_order") List<Event> events = new ArrayList<>();
    protected AssetRegistration() {}
    @PrePersist void timestamps() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @Embeddable public static class Event {
        LocalDateTime occurredAt;
        @Column(length = 80) String actorName;
        @Column(length = 2000) String description;
        protected Event() {}
    }
}
