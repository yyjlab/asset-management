package com.acme.assetmanagement.procurement;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
public class ProcurementRequest {
    public record Create(@NotBlank @Size(max = 36) String requestKey, @NotBlank @Pattern(regexp = "ASSET|CONSUMABLE") String kind,
            @NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 2000) String reason,
            @NotNull @DecimalMin("1") @DecimalMax("1000") @Digits(integer = 4, fraction = 0) BigDecimal quantity,
            @NotBlank @Size(max = 20) String unit, @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal estimatedUnitPrice,
            @FutureOrPresent LocalDate neededDate) {}
    public record Action(@NotNull @Min(0) Long version, @NotBlank @Pattern(regexp = "APPROVE|ORDER|RECEIVE|REJECT|CANCEL") String action,
            @NotBlank @Size(max = 1000) String note, @Positive Long supplierId, @Size(max = 120) String orderNumber,
            LocalDate expectedDate, @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal orderedUnitPrice,
            @Positive Long consumableId, @Min(0) Long consumableVersion,
            @Size(max = 100) List<@NotNull @Positive Long> assetIds, Boolean confirmed) {}
}
