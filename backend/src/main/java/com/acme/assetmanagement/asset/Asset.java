package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.lookup.LookupValue;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "assets")
public class Asset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

    @Column(nullable = false, unique = true, length = 80)
    private String assetTag;

    @Column(unique = true, length = 36)
    private String qrToken;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "ownership_department", length = 120)
    private String ownershipDepartment;

    @Column(length = 200)
    private String cpu;

    @Column(length = 200)
    private String memory;

    @Column(length = 200)
    private String storage;

    @Column(length = 200)
    private String graphicsCard;

    @Column(name = "manufacturer_serial_number", unique = true, length = 160)
    private String manufacturerSerialNumber;

    @Column(name = "screen_size", length = 80)
    private String screenSize;

    @Column(name = "display_resolution", length = 120)
    private String displayResolution;

    @Column(name = "display_interface", length = 160)
    private String displayInterface;

    @Column(name = "order_number", length = 160)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private LookupValue company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private LookupValue model;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private LookupValue category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private LookupValue status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private LookupValue location;

    @Column(precision = 15, scale = 2)
    private BigDecimal purchasePrice;

    @Column(precision = 15, scale = 2)
    private BigDecimal currentValue;

    @Column(nullable = false)
    private boolean checkedOut;

    @Column(length = 120)
    private String assignedTo;

    // Stable association; do not infer account ownership from legacy display names.
    private Long assignedUserId;

    @Column(nullable = false)
    private boolean requestable;

    @Lob
    private String imageUrl;

    @ElementCollection
    @CollectionTable(name = "asset_images", joinColumns = @JoinColumn(name = "asset_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "image_url", nullable = false, length = 1024)
    private List<String> imageUrls = new ArrayList<>();

    @Column(length = 2000)
    private String notes;

    @ElementCollection
    @CollectionTable(name = "asset_related_devices", joinColumns = @JoinColumn(name = "asset_id"))
    @OrderColumn(name = "sort_order")
    private List<RelatedDevice> relatedDevices = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "asset_accessories", joinColumns = @JoinColumn(name = "asset_id"))
    @OrderColumn(name = "sort_order")
    private List<AssetAccessory> accessories = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "asset_custom_parameters", joinColumns = @JoinColumn(name = "asset_id"))
    @OrderColumn(name = "sort_order")
    private List<AssetCustomParameter> customParameters = new ArrayList<>();

    @ManyToMany
    @JoinTable(
            name = "asset_display_bindings",
            joinColumns = @JoinColumn(name = "computer_asset_id"),
            inverseJoinColumns = @JoinColumn(name = "display_asset_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_display_single_computer", columnNames = "display_asset_id")
    )
    @OrderBy("assetTag ASC")
    private Set<Asset> boundDisplays = new LinkedHashSet<>();

    @ManyToMany(mappedBy = "boundDisplays")
    @OrderBy("assetTag ASC")
    private Set<Asset> boundComputers = new LinkedHashSet<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        if (qrToken == null || qrToken.isBlank()) qrToken = UUID.randomUUID().toString();
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public String getAssetTag() { return assetTag; }
    public void setAssetTag(String assetTag) { this.assetTag = assetTag; }
    public String getQrToken() { return qrToken; }
    public void setQrToken(String qrToken) { this.qrToken = qrToken; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwnershipDepartment() { return ownershipDepartment; }
    public void setOwnershipDepartment(String ownershipDepartment) { this.ownershipDepartment = ownershipDepartment; }
    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }
    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }
    public String getStorage() { return storage; }
    public void setStorage(String storage) { this.storage = storage; }
    public String getGraphicsCard() { return graphicsCard; }
    public void setGraphicsCard(String graphicsCard) { this.graphicsCard = graphicsCard; }
    public String getManufacturerSerialNumber() { return manufacturerSerialNumber; }
    public void setManufacturerSerialNumber(String manufacturerSerialNumber) { this.manufacturerSerialNumber = manufacturerSerialNumber; }
    public String getScreenSize() { return screenSize; }
    public void setScreenSize(String screenSize) { this.screenSize = screenSize; }
    public String getDisplayResolution() { return displayResolution; }
    public void setDisplayResolution(String displayResolution) { this.displayResolution = displayResolution; }
    public String getDisplayInterface() { return displayInterface; }
    public void setDisplayInterface(String displayInterface) { this.displayInterface = displayInterface; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public LookupValue getCompany() { return company; }
    public void setCompany(LookupValue company) { this.company = company; }
    public LookupValue getModel() { return model; }
    public void setModel(LookupValue model) { this.model = model; }
    public LookupValue getCategory() { return category; }
    public void setCategory(LookupValue category) { this.category = category; }
    public LookupValue getStatus() { return status; }
    public void setStatus(LookupValue status) { this.status = status; }
    public LookupValue getLocation() { return location; }
    public void setLocation(LookupValue location) { this.location = location; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(BigDecimal purchasePrice) { this.purchasePrice = purchasePrice; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public boolean isCheckedOut() { return checkedOut; }
    public boolean isInMaintenanceOrScrapped() {
        String name = status.getName();
        return name.contains("维修") || name.contains("维护") || name.contains("报废");
    }
    public void setCheckedOut(boolean checkedOut) { this.checkedOut = checkedOut; }
    public String getAssignedTo() { return assignedTo; }
    public Long getAssignedUserId() { return assignedUserId; }
    public void setAssignedUserId(Long id) { this.assignedUserId = id; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
    public boolean isRequestable() { return requestable; }
    public void setRequestable(boolean requestable) { this.requestable = requestable; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls.clear(); this.imageUrls.addAll(imageUrls); }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<RelatedDevice> getRelatedDevices() { return relatedDevices; }
    public void setRelatedDevices(List<RelatedDevice> relatedDevices) { this.relatedDevices.clear(); this.relatedDevices.addAll(relatedDevices); }
    public List<AssetAccessory> getAccessories() { return accessories; }
    public void setAccessories(List<AssetAccessory> accessories) { this.accessories.clear(); this.accessories.addAll(accessories); }
    public List<AssetCustomParameter> getCustomParameters() { return customParameters; }
    public void setCustomParameters(List<AssetCustomParameter> customParameters) { this.customParameters.clear(); this.customParameters.addAll(customParameters); }
    public Set<Asset> getBoundDisplays() { return boundDisplays; }
    public Set<Asset> getBoundComputers() { return boundComputers; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
