package com.acme.assetmanagement.consumable;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public class ConsumableRequest {
    public record Create(@NotBlank @Size(max = 36) String requestKey,
            @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 20) String unit,
            @NotNull @Min(0) @Max(1_000_000_000) Long lowStockThreshold, @Size(max = 1000) String notes) {}
    public record Settings(@NotBlank @Size(max = 36) String requestKey, @NotNull @Min(0) Long version,
            @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 20) String unit,
            @NotNull @Min(0) @Max(1_000_000_000) Long lowStockThreshold, @Size(max = 1000) String notes) {}
    public record Move(@NotBlank @Size(max = 36) String requestKey, @NotNull @Min(0) Long version,
            @NotBlank @Pattern(regexp = "IN|OUT") String kind,
            @NotNull @DecimalMin("1") @DecimalMax("1000000000") @Digits(integer = 10, fraction = 0) BigDecimal quantity,
            @Size(max = 60) String recipientUsername, @NotBlank @Size(max = 1000) String note) {}
}
