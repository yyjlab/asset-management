package com.acme.assetmanagement.integration.deployment;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(DeploymentSecurityConfig.INTAKES)
public class DeploymentIntakeController {
    private final DeploymentIntakeService service;
    public DeploymentIntakeController(DeploymentIntakeService service) { this.service = service; }
    @PostMapping
    public ResponseEntity<DeploymentIntakeService.Result> receive(HttpServletRequest request) {
        var result = service.receive((byte[])request.getAttribute(DeploymentSecurityConfig.BODY));
        return ResponseEntity.status(result.result().equals("pending_review") ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/{externalRegistrationId}")
    public ResponseEntity<DeploymentIntakeService.Result> status(@PathVariable String externalRegistrationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(externalRegistrationId));
    }
}
