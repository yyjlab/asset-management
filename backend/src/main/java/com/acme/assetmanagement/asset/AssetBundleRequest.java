package com.acme.assetmanagement.asset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record AssetBundleRequest(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") String requestKey,
        @Positive Long computerId,
        @PositiveOrZero Long computerVersion,
        @Positive Long cloneSourceId,
        @NotNull @Valid AssetRequest computer,
        @NotNull @Size(max = 8) List<@NotNull @Valid AssetRequest> displays,
        @NotNull @Size(max = 20) List<@NotNull @Valid ExistingDisplay> existingDisplays
) {
    public record ExistingDisplay(@NotNull @Positive Long id, @NotNull @PositiveOrZero Long version) {}
}
