package com.acme.assetmanagement.asset;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Separate from the asset ledger: one current checklist, with append-only cycle history. */
@Entity
@Table(name = "asset_preparations", uniqueConstraints = @UniqueConstraint(columnNames = "asset_id"))
public class AssetPreparation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "asset_id", nullable = false) Asset asset;
    @Column(nullable = false, length = 32) String state = "PENDING";
    @Column(nullable = false) int cycle = 1;
    @Column(length = 160) String previousHolder;
    @Column(length = 1000) String note = "";
    @Column(nullable = false, length = 20) String dataCheck = "UNCHECKED";
    @Column(nullable = false, length = 20) String systemCheck = "UNCHECKED";
    @Column(nullable = false, length = 20) String domainCheck = "UNCHECKED";
    @Column(nullable = false, length = 20) String hardwareCheck = "UNCHECKED";
    @Column(length = 1000) String dataNote = "";
    @Column(length = 1000) String systemNote = "";
    @Column(length = 1000) String domainNote = "";
    @Column(length = 1000) String hardwareNote = "";
    Long readyAssetVersion;
    @Column(nullable = false) LocalDateTime updatedAt = LocalDateTime.now();
    @ElementCollection @CollectionTable(name = "asset_preparation_events", joinColumns = @JoinColumn(name = "preparation_id"))
    @OrderColumn(name = "event_order") List<Event> events = new ArrayList<>();

    @Embeddable
    public static class Event {
        int cycle;
        @Column(length = 160) String actor;
        @Column(length = 32) String action;
        @Column(length = 6000) String description;
        LocalDateTime occurredAt;
        protected Event() {}
        Event(int cycle, String actor, String action, String description) {
            this.cycle = cycle; this.actor = actor; this.action = action; this.description = description; this.occurredAt = LocalDateTime.now();
        }
    }
}
