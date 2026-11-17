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
    private final AssetImageStorageService storage;

    public PublicAssetController(AssetService assetService, AssetImageStorageService storage) {
        this.assetService = assetService;
        this.storage = storage;
    }

    @GetMapping("/{qrToken}")
    public ResponseEntity<PublicAssetResponse> getByQrToken(@PathVariable String qrToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(assetService.getSharedByQrToken(qrToken));
    }

    @GetMapping("/{qrToken}/images/{index}")
    public ResponseEntity<org.springframework.core.io.Resource> image(@PathVariable String qrToken, @PathVariable String index) {
        if (!index.matches("[0-9]{1,2}")) throw new com.acme.assetmanagement.common.ApiException(
                org.springframework.http.HttpStatus.NOT_FOUND, "图片不存在");
        String fileName = assetService.getSharedImage(qrToken, Integer.parseInt(index));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(org.springframework.http.MediaType.parseMediaType(storage.mediaType(fileName)))
                .body(storage.load(fileName));
    }
}
