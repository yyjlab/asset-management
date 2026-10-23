package com.acme.assetmanagement.setting;

import com.acme.assetmanagement.asset.DomainJoinStatus;
import jakarta.persistence.*;

@Entity
@Table(name = "domain_status_preferences")
public class DomainStatusPreference {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DomainJoinStatus code;
    @Version
    private Long version;
    @Column(nullable = false, length = 200)
    private String description;
    @Column(nullable = false)
    private int sortOrder;

    protected DomainStatusPreference() {}
    DomainStatusPreference(DomainJoinStatus code) { this.code = code; }
    public DomainJoinStatus getCode() { return code; }
    public Long getVersion() { return version; }
    public String getDescription() { return description; }
    public int getSortOrder() { return sortOrder; }
    public void update(String description, int sortOrder) {
        this.description = description;
        this.sortOrder = sortOrder;
    }
}
