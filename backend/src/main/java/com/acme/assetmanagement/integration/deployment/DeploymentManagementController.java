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
    private final DeploymentProperties properties;
    private final DeploymentRegistrationRepository registrations;
    private final ObjectMapper json;

    public DeploymentManagementController(DeploymentProperties properties,
            DeploymentRegistrationRepository registrations, ObjectMapper json) {
        this.properties = properties;
        this.registrations = registrations;
        this.json = json;
    }

    public record Status(boolean enabled, boolean secureRequest, String endpoint, Instant lastReceivedAt) {}
    public record Entry(String externalRegistrationId, String assetTag, Long assetId, Instant receivedAt,
            String credentialId, DeploymentRegistrationRequest.Operator declaredOperator,
            String result, List<DeploymentRegistrationResult.Difference> differences, List<String> warnings) {}
    public record History(List<Entry> items, int page, int totalPages, long totalElements) {}

    @GetMapping("/status")
    public ResponseEntity<Status> status(jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Status(properties.isEnabled(),
                request.isSecure(), DeploymentSecurityConfig.PATH,
                registrations.findFirstByOrderByCreatedAtDescExternalIdDesc().map(row -> row.createdAt).orElse(null)));
    }

    @GetMapping("/registrations")
    public ResponseEntity<History> history(@RequestParam(defaultValue = "0") int page) {
        if (page < 0 || page > 100000)
            throw new DeploymentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "页码须在0到100000之间");
        var result = registrations.findAllByOrderByCreatedAtDescExternalIdDesc(PageRequest.of(page, 20));
        var entries = result.getContent().stream().map(row -> {
            var response = json.readValue(row.responseJson, DeploymentRegistrationResult.class);
            var request = json.readValue(row.requestJson, DeploymentRegistrationRequest.class);
            return new Entry(row.externalId, row.assetTag, row.assetId, row.createdAt, row.credentialId,
                    request.operator(), response.result(), response.differences(), response.warnings());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new History(entries, page, result.getTotalPages(), result.getTotalElements()));
    }
}
