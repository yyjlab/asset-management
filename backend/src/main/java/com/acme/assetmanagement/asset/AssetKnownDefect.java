package com.acme.assetmanagement.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.LocalDateTime;

@Embeddable
public class AssetKnownDefect {
    @Column(name = "defect_id", nullable = false, length = 36)
    private String id;
    @Column(nullable = false, length = 300)
    private String description;
    @Column(nullable = false, length = 120)
    private String recordedBy;
    @Column(nullable = false)
    private LocalDateTime recordedAt;
    @Column(length = 120)
    private String archivedBy;
    private LocalDateTime archivedAt;

    protected AssetKnownDefect() {}

    public AssetKnownDefect(String id, String description, String recordedBy, LocalDateTime recordedAt,
                            String archivedBy, LocalDateTime archivedAt) {
        this.id = id;
        this.description = description;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.archivedBy = archivedBy;
        this.archivedAt = archivedAt;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public String getRecordedBy() { return recordedBy; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public String getArchivedBy() { return archivedBy; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
}
