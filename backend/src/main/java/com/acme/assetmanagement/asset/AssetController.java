package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
    private final AssetService service;
    private final AssetQrCodeService qrCodeService;
    private final AssetCsvTemplateService csvTemplateService;
    private final AuditLogService auditLogService;
    private final AssetCsvImportService csvImportService;

    public AssetController(AssetService service, AssetQrCodeService qrCodeService, AssetCsvTemplateService csvTemplateService,
                           AuditLogService auditLogService, AssetCsvImportService csvImportService) {
        this.service = service;
        this.qrCodeService = qrCodeService;
        this.csvTemplateService = csvTemplateService;
        this.auditLogService = auditLogService;
        this.csvImportService = csvImportService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ASSET_VIEW')")
    public List<AssetResponse> list(@RequestParam(defaultValue = "") String search) {
        return service.list(search);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ASSET_VIEW')")
    public AssetResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping(value = {"/export.csv", "/export/template.csv"}, produces = "text/csv;charset=UTF-8")
    @PreAuthorize("hasAuthority('ASSET_VIEW') and hasAuthority('ASSET_EXPORT')")
    public ResponseEntity<byte[]> exportAssets() {
        List<AssetResponse> assets = service.list("");
        auditLogService.success(AuditAction.CSV_TEMPLATE_EXPORT, "CSV", null, "授权范围资产数据",
                "导出授权范围资产数据，共 " + assets.size() + " 项", Map.of("导出数量", assets.size()));
        String disposition = ContentDisposition.attachment()
                .filename("asset-data.csv", StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", disposition)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csvTemplateService.createAssetExport(assets));
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ASSET_CREATE') and hasAuthority('ASSET_IMPORT')")
    public AssetCsvImportService.PreviewResponse previewImport(@RequestPart("file") MultipartFile file) {
        service.requireImportScope();
        return csvImportService.preview(file);
    }

    @PostMapping(value = "/import/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ASSET_CREATE') and hasAuthority('ASSET_IMPORT') and hasAuthority('ASSET_CHECKOUT')")
    public AssetCsvImportService.ImportResponse commitImport(@RequestPart("file") MultipartFile file) {
        service.requireImportScope();
        return csvImportService.commit(file);
    }

    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAuthority('ASSET_VIEW')")
    public ResponseEntity<byte[]> qrCode(@PathVariable Long id) {
        AssetResponse asset = service.get(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .header("Content-Disposition", "inline; filename=asset-" + asset.assetTag() + "-label.png")
                .body(qrCodeService.generatePng(asset));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ASSET_CREATE')")
    @ResponseStatus(HttpStatus.CREATED)
    public AssetResponse create(@RequestParam(required = false) Long cloneSourceId,
                                @Valid @RequestBody AssetRequest request) {
        return service.create(request, cloneSourceId);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ASSET_EDIT')")
    public AssetResponse update(@PathVariable Long id, @Valid @RequestBody AssetRequest request,
                                @RequestHeader(value = "If-Match", required = false) String version) {
        return service.update(id, request, version);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAuthority('ASSET_RETURN')")
    public AssetResponse returnAsset(@PathVariable Long id,
                                     @RequestHeader(value = "If-Match", required = false) String version) {
        return service.returnAsset(id, version);
    }

    @PostMapping("/qr/{qrToken}/return")
    @PreAuthorize("hasAuthority('ASSET_RETURN')")
    public AssetResponse returnAssetByQrToken(@PathVariable String qrToken,
                                              @RequestHeader(value = "If-Match", required = false) String version) {
        return service.returnAssetByQrToken(qrToken, version);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ASSET_DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('ASSET_VIEW')")
    public Map<String, Long> summary() {
        List<AssetResponse> assets = service.list("");
        return Map.of(
                "total", (long) assets.size(),
                "checkedOut", assets.stream().filter(asset -> asset.checkedOut() && !isScrapped(asset)).count(),
                "available", assets.stream().filter(asset -> !asset.checkedOut() && !isMaintenance(asset) && !isScrapped(asset)).count(),
                "maintenance", assets.stream().filter(AssetController::isMaintenance).count(),
                "scrapped", assets.stream().filter(AssetController::isScrapped).count()
        );
    }

    private static boolean isMaintenance(AssetResponse asset) {
        String status = asset.status().name();
        return status.contains("维修") || status.contains("维护");
    }

    private static boolean isScrapped(AssetResponse asset) {
        return asset.status().name().contains("报废");
    }
}
