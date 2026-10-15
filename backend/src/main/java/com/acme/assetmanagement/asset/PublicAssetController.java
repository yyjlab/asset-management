package com.acme.assetmanagement.asset;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/public/assets")
public class PublicAssetController {
    private final AssetService assetService;

    public PublicAssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping("/{qrToken}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('ASSET_VIEW')")
    public ResponseEntity<PublicAssetResponse> getByQrToken(@PathVariable String qrToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(assetService.getPublicByQrToken(qrToken));
    }
}
