package com.acme.assetmanagement.integration.deployment;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/settings/ad-deployment")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Transactional(readOnly = true)
public class DeploymentManagementController {
    private final DeploymentAccessService access;
    private final DeploymentRegistrationRepository registrations;
    private final DeploymentIntakeRepository intakes;
    private final ObjectMapper json;

    public DeploymentManagementController(DeploymentAccessService access,
            DeploymentRegistrationRepository registrations, DeploymentIntakeRepository intakes, ObjectMapper json) {
        this.access = access;
        this.registrations = registrations;
        this.intakes = intakes;
        this.json = json;
    }

    public record Status(boolean enabled, boolean secureRequest, boolean allowHttp, String endpoint, Instant lastReceivedAt) {}
    public record Update(@jakarta.validation.constraints.NotNull Boolean enabled,
                         @jakarta.validation.constraints.NotNull Boolean allowHttp,
                         boolean acknowledgeHttpRisk) {}
    public record Entry(String externalRegistrationId, String assetTag, Long assetId, Instant receivedAt,
            String credentialId, DeploymentRegistrationRequest.Operator declaredOperator,
            String result, List<DeploymentRegistrationResult.Difference> differences, List<String> warnings) {}
    public record History(List<Entry> items, int page, int totalPages, long totalElements) {}

    @GetMapping("/status")
    public ResponseEntity<Status> status(jakarta.servlet.http.HttpServletRequest request) {
        var config = access.current();
        Instant latest = registrations.findFirstByOrderByCreatedAtDescExternalIdDesc().map(row -> row.createdAt).orElse(null);
        Instant intake = intakes.findFirstByOrderByReceivedAtDescExternalIdDesc().map(row -> row.receivedAt).orElse(null);
        if (intake != null && (latest == null || intake.isAfter(latest))) latest = intake;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Status(config.enabled(),
                request.isSecure(), config.allowHttp(), DeploymentSecurityConfig.INTAKES, latest));
    }

    @PutMapping("/settings")
    @Transactional
    public ResponseEntity<Status> update(@jakarta.validation.Valid @RequestBody Update value,
            jakarta.servlet.http.HttpServletRequest request) {
        if (value.enabled() && value.allowHttp() && !value.acknowledgeHttpRisk())
            throw new DeploymentApiException(HttpStatus.BAD_REQUEST, "HTTP_RISK_CONFIRMATION_REQUIRED",
                    "内网HTTP会明文传输凭据和资产资料，须明确确认风险，仅限受信内网使用");
        access.save(value.enabled(), value.allowHttp());
        return status(request);
    }

    @GetMapping("/registrations")
    public ResponseEntity<History> history(@RequestParam(defaultValue = "0") int page) {
        if (page < 0 || page > 100000)
            throw new DeploymentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "页码须在0到100000之间");
        var result = registrations.findAllByOrderByCreatedAtDescExternalIdDesc(PageRequest.of(page, 20));
        var entries = result.getContent().stream().map(row -> {
            var response = json.readValue(row.responseJson, DeploymentRegistrationResult.class);
            var operator = json.treeToValue(json.readTree(row.requestJson).get("operator"), DeploymentRegistrationRequest.Operator.class);
            return new Entry(row.externalId, row.assetTag, row.assetId, row.createdAt, row.credentialId,
                    operator, response.result(), response.differences(), response.warnings());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new History(entries, page, result.getTotalPages(), result.getTotalElements()));
    }
}
