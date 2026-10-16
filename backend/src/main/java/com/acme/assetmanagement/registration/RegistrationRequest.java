package com.acme.assetmanagement.registration;

import jakarta.validation.constraints.*;

public final class RegistrationRequest {
    private RegistrationRequest() {}
    public record Submit(@NotBlank @Size(max = 36) String requestKey,
            @NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 120) String model,
            @Size(max = 160) String serialNumber, @Size(max = 120) String department,
            @Size(max = 120) String location, @Size(max = 200) String cpu,
            @Size(max = 200) String memory, @Size(max = 200) String storage,
            @Size(max = 2000) String notes, @Min(0) Long version) {}
    public record Review(@NotNull @Min(0) Long version,
            @NotBlank @Pattern(regexp = "NEEDS_INFO|REJECTED") String decision,
            @NotBlank @Size(max = 1000) String note) {}
    public record Withdraw(@NotNull @Min(0) Long version, @NotBlank @Size(max = 1000) String note) {}
    public record ApproveCreate(@NotNull @Min(0) Long version, @NotNull Long companyId,
            @NotNull Long categoryId, @NotNull Long locationId, @Size(max = 1000) String note,
            @AssertTrue(message = "请明确确认已核对重复设备和领用账号") boolean confirmed) {}
    public record ApproveExisting(@NotNull @Min(0) Long version, @NotNull Long assetId,
            @NotNull @Min(0) Long assetVersion, @Size(max = 1000) String note,
            @AssertTrue(message = "请明确确认实物及资产编号一致") boolean confirmed) {}
}
