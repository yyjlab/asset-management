package com.acme.assetmanagement.integration.deployment;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/ad-deployment")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class DeploymentIntakeManagementController {
    private final DeploymentIntakeService service;
    public DeploymentIntakeManagementController(DeploymentIntakeService service) { this.service = service; }
    @GetMapping("/intakes")
    public ResponseEntity<DeploymentIntakeService.History> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "pending_review") String state) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(page, state));
    }
    @GetMapping("/intake-options")
    public ResponseEntity<DeploymentIntakeService.Options> options() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options());
    }
    @PostMapping(value = "/intakes/{externalRegistrationId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeploymentIntakeService.Entry> approve(@PathVariable String externalRegistrationId, @RequestBody byte[] body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.review(externalRegistrationId, body, true));
    }
    @PostMapping(value = "/intakes/{externalRegistrationId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeploymentIntakeService.Entry> reject(@PathVariable String externalRegistrationId, @RequestBody byte[] body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.review(externalRegistrationId, body, false));
    }
    @PostMapping(value = "/intakes/{externalRegistrationId}/resubmit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeploymentIntakeService.Entry> resubmit(@PathVariable String externalRegistrationId, @RequestBody byte[] body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resubmit(externalRegistrationId, body));
    }
}
