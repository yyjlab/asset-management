package com.acme.assetmanagement.asset;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets/bundles")
public class AssetBundleController {
    private final AssetBundleService service;
    public AssetBundleController(AssetBundleService service) { this.service = service; }
    @PostMapping
    @PreAuthorize("hasAuthority('ASSET_VIEW') and hasAuthority('ASSET_CREATE') and hasAuthority('ASSET_EDIT')")
    public AssetBundleService.Result save(@Valid @RequestBody AssetBundleRequest request) { return service.save(request); }
    @GetMapping("/{requestKey}")
    @PreAuthorize("hasAuthority('ASSET_VIEW')")
    public AssetBundleService.Result get(@PathVariable String requestKey) { return service.get(requestKey); }
}
