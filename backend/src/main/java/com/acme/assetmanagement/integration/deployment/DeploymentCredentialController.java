package com.acme.assetmanagement.integration.deployment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/settings/ad-deployment/credentials")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class DeploymentCredentialController {
    private final DeploymentCredentialService credentials;
    private final DeploymentAccessService access;
    public DeploymentCredentialController(DeploymentCredentialService credentials, DeploymentAccessService access) {
        this.credentials = credentials; this.access = access;
    }
    public record Create(@NotBlank @Size(max = 80) String name, @NotNull @Future Instant expiresAt) {}
    @GetMapping public List<DeploymentCredentialService.Info> list() { return credentials.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<DeploymentCredentialService.Issued> create(@Valid @RequestBody Create request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (!access.allowsRequest(httpRequest.isSecure())) throw new DeploymentApiException(HttpStatus.FORBIDDEN, "HTTPS_REQUIRED", "请使用HTTPS，或先在接入设置中确认开启内网HTTP");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(credentials.issue(request.name(), request.expiresAt()));
    }
    @DeleteMapping("/{id}") public DeploymentCredentialService.Info revoke(@PathVariable String id) { return credentials.revoke(id); }
}
