package com.acme.assetmanagement.integration.deployment;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
public class DeploymentRegistrationController {
    private final DeploymentRegistrationService registrations;
    private final ObjectMapper json;
    public DeploymentRegistrationController(DeploymentRegistrationService registrations, ObjectMapper json) {
        this.registrations = registrations; this.json = json;
    }
    @PostMapping(DeploymentSecurityConfig.PATH)
    @PreAuthorize("hasAuthority('AD_DEPLOYMENT_REGISTER')")
    public ResponseEntity<DeploymentRegistrationResult> register(@RequestAttribute(DeploymentSecurityConfig.BODY) byte[] body) {
        var result = registrations.register(DeploymentRegistrationRequest.parse(body, json));
        return ResponseEntity.status(result.httpStatus()).body(result);
    }
    @GetMapping(DeploymentSecurityConfig.CONNECTION)
    @PreAuthorize("hasAuthority('AD_DEPLOYMENT_REGISTER')")
    public ResponseEntity<DeploymentRegistrationService.ConnectionInfo> connection() {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(registrations.connectionInfo());
    }
}
