package com.acme.assetmanagement.asset;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/public/asset-images")
public class AssetImageController {
    private final AssetImageStorageService storage;
    private final AssetService service;

    public AssetImageController(AssetImageStorageService storage, AssetService service) {
        this.storage = storage;
        this.service = service;
    }

    @GetMapping("/{fileName}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('ASSET_VIEW')")
    public ResponseEntity<Resource> image(@PathVariable String fileName) {
        service.requireImageReference("/api/public/asset-images/" + fileName);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(storage.mediaType(fileName)))
                .body(storage.load(fileName));
    }
}
