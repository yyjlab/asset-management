package com.acme.assetmanagement.lookup;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "lookup_values", uniqueConstraints = @UniqueConstraint(columnNames = {"lookup_type", "name"}))
public class LookupValue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "lookup_type", nullable = false, length = 30)
    private LookupType type;

    @Column(nullable = false, length = 200)
    private String name;

    // Nullable metadata: existing options remain enabled without rewriting any asset or option row.
    private Boolean disabled;
    private Long optionVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_profile", length = 30)
    private AssetProfile assetProfile;

    @ElementCollection
    @CollectionTable(name = "category_parameter_templates", joinColumns = @JoinColumn(name = "category_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "parameter_name", nullable = false, length = 120)
    private List<String> parameterTemplate = new ArrayList<>();

    public List<String> getParameterTemplate() { return parameterTemplate; }
    public void setParameterTemplate(List<String> names) {
        parameterTemplate.clear();
        parameterTemplate.addAll(names);
    }

    protected LookupValue() {
    }

    public LookupValue(LookupType type, String name) {
        this(type, name, type == LookupType.CATEGORY ? AssetProfile.infer(name) : null);
    }

    public LookupValue(LookupType type, String name, AssetProfile assetProfile) {
        this.type = type;
        this.name = name;
        this.assetProfile = type == LookupType.CATEGORY
                ? (assetProfile == null ? AssetProfile.GENERAL : assetProfile)
                : null;
    }

    public Long getId() { return id; }
    public LookupType getType() { return type; }
    public String getName() { return name; }
    public boolean isDisabled() { return Boolean.TRUE.equals(disabled); }
    public long getOptionVersion() { return optionVersion == null ? 0 : optionVersion; }
    public void setDisabled(boolean disabled) { this.disabled = disabled; this.optionVersion = getOptionVersion() + 1; }
    public AssetProfile getAssetProfile() { return assetProfile; }
    public void setAssetProfile(AssetProfile assetProfile) {
        this.assetProfile = type == LookupType.CATEGORY ? assetProfile : null;
    }
}
