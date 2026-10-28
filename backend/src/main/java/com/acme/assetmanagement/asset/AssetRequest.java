package com.acme.assetmanagement.asset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public record AssetRequest(
        @Pattern(regexp = "(?:|\\d{12})", message = "资产编号必须为空或12位数字") String assetTag,
        @NotBlank(message = "资产名称不能为空") @Size(max = 160) String name,
        @Size(max = 120) String ownershipDepartment,
        @Size(max = 200) String cpu,
        @Size(max = 200) String memory,
        @Size(max = 200) String storage,
        @Size(max = 200) String graphicsCard,
        @Size(max = 160) String manufacturerSerialNumber,
        @Size(max = 80) String screenSize,
        @Size(max = 120) String displayResolution,
        @Size(max = 160) String displayInterface,
        @Size(max = 160) String orderNumber,
        @NotNull(message = "公司不能为空") Long companyId,
        Long modelId,
        @Size(max = 120) String modelName,
        @NotNull(message = "分类不能为空") Long categoryId,
        @NotNull(message = "状态不能为空") Long statusId,
        @NotNull(message = "位置不能为空") Long locationId,
        @Digits(integer = 13, fraction = 2) @DecimalMin(value = "0.0", message = "采购价格不能小于0") BigDecimal purchasePrice,
        @Digits(integer = 13, fraction = 2) @DecimalMin(value = "0.0", message = "当前价值不能小于0") BigDecimal currentValue,
        boolean checkedOut,
        @Size(max = 120) String assignedTo,
        String imageUrl,
        @Size(max = 5, message = "资产图片最多上传5张") List<@NotBlank(message = "资产图片不能为空") String> imageUrls,
        @Size(max = 2000) String notes,
        List<Long> boundDisplayIds,
        Long boundComputerId,
        List<@Valid RelatedDeviceRequest> relatedDevices,
        List<@Valid AccessoryRequest> accessories,
        @Size(max = 5, message = "自定义参数最多5项") List<@Valid CustomParameterRequest> customParameters,
        Long assignedUserId,
        DomainJoinStatus domainJoinStatus
) {
    public AssetRequest withBindings(List<Long> displayIds, Long computerId) {
        return new AssetRequest(assetTag, name, ownershipDepartment, cpu, memory, storage, graphicsCard,
                manufacturerSerialNumber, screenSize, displayResolution, displayInterface, orderNumber,
                companyId, modelId, modelName, categoryId, statusId, locationId, purchasePrice, currentValue,
                checkedOut, assignedTo, imageUrl, imageUrls, notes, displayIds, computerId, relatedDevices,
                accessories, customParameters, assignedUserId, domainJoinStatus);
    }

    public record RelatedDeviceRequest(
            @NotBlank(message = "随附配件名称不能为空") @Size(max = 120) String name,
            @Size(max = 160) String model,
            @Size(max = 160) String serialNumber,
            @Size(max = 160) String orderNumber,
            @Size(max = 300) String specification,
            @Min(value = 1, message = "随附配件数量至少为1") @Max(value = 9999, message = "随附配件数量不能超过9999") int quantity
    ) {}

    public record AccessoryRequest(
            @NotBlank(message = "配件名称不能为空") @Size(max = 120) String name,
            @Size(max = 200) String specification,
            @Min(value = 1, message = "配件数量至少为1") @Max(value = 9999, message = "配件数量不能超过9999") int quantity
    ) {}

    public record CustomParameterRequest(
            @NotBlank(message = "参数名称不能为空") @Size(max = 120, message = "参数名称不能超过120个字符") String name,
            @NotBlank(message = "参数值不能为空") @Size(max = 1000, message = "参数值不能超过1000个字符") String value
    ) {}
}
