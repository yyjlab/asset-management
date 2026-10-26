package com.acme.assetmanagement.handover;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class HandoverRequest {
    private HandoverRequest() {}
    public record Create(
            @NotBlank @Size(max = 36) String requestKey,
            @NotBlank @Pattern(regexp = "ONBOARDING|OFFBOARDING") String kind,
            @Positive Long employeeUserId,
            @Size(max = 80) String employeeName,
            @Size(max = 120) String department,
            LocalDate plannedDate,
            @Size(max = 160) String approvalReference,
            @Size(max = 2000) String notes,
            @NotNull @Size(max = 100) List<@NotNull @Positive Long> assetIds,
            boolean includeAssignedAssets,
            boolean allowLegacyAssets,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 160) String> manualItems,
            Boolean ledgerEnabled,
            @Size(max = 100) java.util.Map<@Positive Long, @NotNull @PositiveOrZero Long> assetVersions) {}
    public record Check(@NotNull @Min(0) Long version, boolean checked) {}
    public record Confirm(@NotNull @Min(0) Long version, boolean offline, @Size(max = 1000) String note) {}
    public record Action(@NotNull @Min(0) Long version, @Size(max = 1000) String note, Boolean confirmed) {}
    public record LinkEmployee(@NotNull @Min(0) Long version, @NotNull @Positive Long employeeUserId) {}
    public record BindAsset(@NotNull @Min(0) Long version, @NotNull @Positive Long assetId,
                            @NotNull @PositiveOrZero Long assetVersion) {}
}
