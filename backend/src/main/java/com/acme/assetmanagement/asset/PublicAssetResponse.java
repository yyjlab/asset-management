package com.acme.assetmanagement.asset;

import java.time.LocalDateTime;
import java.util.List;

public record PublicAssetResponse(
        String assetTag,
        String name,
        String manufacturerSerialNumber,
        String computerModel,
        String cpu,
        String memory,
        String storage,
        String graphicsCard,
        String screenSize,
        String displayResolution,
        String displayInterface,
        String orderNumber,
        String company,
        String ownershipDepartment,
        String category,
        String assetProfile,
        String status,
        String location,
        boolean checkedOut,
        String assignedTo,
        String imageUrl,
        List<String> imageUrls,
        List<PublicAssetLink> boundDisplays,
        PublicAssetLink boundComputer,
        List<PublicRelatedDevice> relatedDevices,
        LocalDateTime updatedAt
) {
    public static PublicAssetResponse from(Asset asset) {
        return from(asset, ignored -> true);
    }
    public static PublicAssetResponse from(Asset asset, java.util.function.Predicate<Asset> visible) {
        List<String> images = asset.getImageUrls().isEmpty()
                ? asset.getImageUrl() == null || asset.getImageUrl().isBlank() ? List.of() : List.of(asset.getImageUrl())
                : List.copyOf(asset.getImageUrls());
        List<PublicRelatedDevice> devices = new java.util.ArrayList<>();
        asset.getRelatedDevices().forEach(device -> devices.add(new PublicRelatedDevice(
                device.getName(), device.getModel(), device.getSerialNumber(), device.getSpecification(), device.getQuantity()
        )));
        asset.getAccessories().forEach(accessory -> devices.add(new PublicRelatedDevice(
                accessory.getName(), null, null, accessory.getSpecification(), accessory.getQuantity()
        )));
        return new PublicAssetResponse(
                asset.getAssetTag(), asset.getName(), asset.getManufacturerSerialNumber(),
                asset.getModel() == null ? null : asset.getModel().getName(),
                asset.getCpu(), asset.getMemory(), asset.getStorage(), asset.getGraphicsCard(),
                asset.getScreenSize(), asset.getDisplayResolution(), asset.getDisplayInterface(), asset.getOrderNumber(),
                asset.getCompany() == null ? null : asset.getCompany().getName(),
                asset.getOwnershipDepartment(),
                asset.getCategory() == null ? null : asset.getCategory().getName(),
                asset.getCategory() == null || asset.getCategory().getAssetProfile() == null
                        ? "GENERAL" : asset.getCategory().getAssetProfile().name(),
                asset.getStatus() == null ? null : asset.getStatus().getName(),
                asset.getLocation() == null ? null : asset.getLocation().getName(),
                asset.isCheckedOut(), asset.getAssignedTo(), images.isEmpty() ? null : images.getFirst(), images,
                asset.getBoundDisplays().stream().filter(visible).map(PublicAssetLink::from).toList(),
                asset.getBoundComputers().stream().filter(visible).findFirst().map(PublicAssetLink::from).orElse(null),
                List.copyOf(devices), asset.getUpdatedAt()
        );
    }

    public record PublicAssetLink(String assetTag, String name, String model, String category) {
        static PublicAssetLink from(Asset asset) {
            return new PublicAssetLink(asset.getAssetTag(), asset.getName(),
                    asset.getModel() == null ? null : asset.getModel().getName(),
                    asset.getCategory() == null ? null : asset.getCategory().getName());
        }
    }

    public record PublicRelatedDevice(
            String name,
            String model,
            String serialNumber,
            String specification,
            int quantity
    ) {}
}
