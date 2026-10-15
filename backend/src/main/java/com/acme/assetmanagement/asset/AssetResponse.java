package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.lookup.LookupController.LookupResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AssetResponse(
        Long id,
        long version,
        String qrToken,
        String assetTag,
        String name,
        String ownershipDepartment,
        String cpu,
        String memory,
        String storage,
        String graphicsCard,
        String manufacturerSerialNumber,
        String screenSize,
        String displayResolution,
        String displayInterface,
        String orderNumber,
        LookupResponse company,
        LookupResponse model,
        LookupResponse category,
        LookupResponse status,
        LookupResponse location,
        BigDecimal purchasePrice,
        BigDecimal currentValue,
        boolean checkedOut,
        String assignedTo,
        String imageUrl,
        List<String> imageUrls,
        String notes,
        List<AssetLinkResponse> boundDisplays,
        AssetLinkResponse boundComputer,
        List<RelatedDeviceResponse> relatedDevices,
        List<AccessoryResponse> accessories,
        List<CustomParameterResponse> customParameters,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long assignedUserId
) {
    public static AssetResponse from(Asset asset) {
        return from(asset, ignored -> true);
    }
    public static AssetResponse from(Asset asset, java.util.function.Predicate<Asset> visible) {
        List<String> images = asset.getImageUrls().isEmpty()
                ? asset.getImageUrl() == null || asset.getImageUrl().isBlank() ? List.of() : List.of(asset.getImageUrl())
                : List.copyOf(asset.getImageUrls());
        return new AssetResponse(
                asset.getId(), asset.getVersion(), asset.getQrToken(), asset.getAssetTag(), asset.getName(), asset.getOwnershipDepartment(),
                asset.getCpu(), asset.getMemory(), asset.getStorage(), asset.getGraphicsCard(),
                asset.getManufacturerSerialNumber(),
                asset.getScreenSize(), asset.getDisplayResolution(), asset.getDisplayInterface(), asset.getOrderNumber(),
                LookupResponse.from(asset.getCompany()), LookupResponse.from(asset.getModel()),
                LookupResponse.from(asset.getCategory()), LookupResponse.from(asset.getStatus()),
                LookupResponse.from(asset.getLocation()), asset.getPurchasePrice(), asset.getCurrentValue(),
                asset.isCheckedOut(), asset.getAssignedTo(), images.isEmpty() ? null : images.getFirst(), images,
                asset.getNotes(),
                asset.getBoundDisplays().stream().filter(visible).map(AssetLinkResponse::from).toList(),
                asset.getBoundComputers().stream().filter(visible).findFirst().map(AssetLinkResponse::from).orElse(null),
                asset.getRelatedDevices().stream().map(RelatedDeviceResponse::from).toList(),
                asset.getAccessories().stream().map(AccessoryResponse::from).toList(),
                asset.getCustomParameters().stream().map(CustomParameterResponse::from).toList(),
                asset.getCreatedAt(), asset.getUpdatedAt(), asset.getAssignedUserId()
        );
    }

    public record AssetLinkResponse(Long id, String assetTag, String name, String model, String category) {
        static AssetLinkResponse from(Asset asset) {
            return new AssetLinkResponse(asset.getId(), asset.getAssetTag(), asset.getName(),
                    asset.getModel() == null ? null : asset.getModel().getName(),
                    asset.getCategory() == null ? null : asset.getCategory().getName());
        }
    }

    public record RelatedDeviceResponse(String name, String model, String serialNumber, String orderNumber,
                                        String specification, int quantity) {
        static RelatedDeviceResponse from(RelatedDevice device) {
            return new RelatedDeviceResponse(device.getName(), device.getModel(), device.getSerialNumber(),
                    device.getOrderNumber(), device.getSpecification(), device.getQuantity());
        }
    }

    public record AccessoryResponse(String name, String specification, int quantity) {
        static AccessoryResponse from(AssetAccessory accessory) {
            return new AccessoryResponse(accessory.getName(), accessory.getSpecification(), accessory.getQuantity());
        }
    }

    public record CustomParameterResponse(String name, String value) {
        static CustomParameterResponse from(AssetCustomParameter parameter) {
            return new CustomParameterResponse(parameter.getName(), parameter.getValue());
        }
    }
}
