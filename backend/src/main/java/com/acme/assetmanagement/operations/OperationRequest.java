package com.acme.assetmanagement.operations;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Map;

public class OperationRequest {
    public record Create(@NotBlank @Size(max = 36) String requestKey,
            @NotBlank @Pattern(regexp = "ISSUE|RETURN|TRANSFER|CORRECTION|REPAIR") String kind,
            @NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 2000) String description,
            @Positive Long assetId, @Positive Long ownerId, @Size(max = 60) String targetUsername,
            @Size(max = 4) Map<@NotBlank @Pattern(regexp = "cpu|memory|storage|ownershipDepartment") String, @NotNull @Size(max = 200) String> proposed) {}
    public record Action(@NotNull @Min(0) Long version,
            @NotBlank @Pattern(regexp = "APPROVE|PROGRESS|RESOLVE|CONFIRM|COMPLETE|REJECT|CANCEL") String action,
            @Size(max = 1000) String note, @Positive Long assetId, @Min(0) Long assetVersion,
            @Pattern(regexp = "FIXED|SCRAP") String outcome,
            @DecimalMin("0.00") @DecimalMax("9999999999.99") @Digits(integer = 10, fraction = 2) BigDecimal repairCost,
            Boolean confirmed) {}
}
