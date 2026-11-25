package com.acme.assetmanagement.asset;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/asset-preparations")
public class AssetPreparationController {
    private final AssetPreparationService service;
    public AssetPreparationController(AssetPreparationService service) { this.service = service; }
    @GetMapping @PreAuthorize("hasAuthority('ASSET_VIEW')")
    public ResponseEntity<List<AssetPreparationService.Overview>> list() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list()); }
    @GetMapping("/{assetId}") @PreAuthorize("hasAuthority('ASSET_VIEW')")
    public ResponseEntity<AssetPreparationService.Detail> get(@PathVariable Long assetId) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(assetId)); }
    @PostMapping("/{assetId}") @PreAuthorize("hasAuthority('ASSET_VIEW') and hasAuthority('ASSET_EDIT')")
    public AssetPreparationService.Detail action(@PathVariable Long assetId, @Valid @RequestBody AssetPreparationService.Action request) {
        return service.action(assetId, request);
    }
}
