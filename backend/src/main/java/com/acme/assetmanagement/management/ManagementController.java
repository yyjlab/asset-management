package com.acme.assetmanagement.management;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController @RequestMapping("/api/management")
public class ManagementController {
    private final EquipmentKitService kits;
    private final AssetSupportService support;
    public ManagementController(EquipmentKitService kits, AssetSupportService support) { this.kits = kits; this.support = support; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/kits") public EquipmentKitService.Page kits(@RequestParam(defaultValue = "0") int page) { return kits.list(page); }
    @GetMapping("/kits/{id}") public EquipmentKitService.Detail kit(@PathVariable Long id) { return kits.get(id); }
    @PostMapping("/kits") @ResponseStatus(HttpStatus.CREATED) public EquipmentKitService.Detail createKit(@Valid @RequestBody EquipmentKitService.Write body) { return kits.save(null, body); }
    @PutMapping("/kits/{id}") public EquipmentKitService.Detail updateKit(@PathVariable Long id, @Valid @RequestBody EquipmentKitService.Write body) { return kits.save(id, body); }
    @GetMapping("/suppliers") public AssetSupportService.Suppliers suppliers(@RequestParam(defaultValue = "0") int page) { return support.suppliers(page); }
    @GetMapping("/suppliers/{id}") public AssetSupportService.SupplierView supplier(@PathVariable Long id) { return support.supplier(id); }
    @PostMapping("/suppliers") @ResponseStatus(HttpStatus.CREATED) public AssetSupportService.SupplierView createSupplier(@Valid @RequestBody AssetSupportService.SupplierWrite body) { return support.saveSupplier(null, body); }
    @PutMapping("/suppliers/{id}") public AssetSupportService.SupplierView updateSupplier(@PathVariable Long id, @Valid @RequestBody AssetSupportService.SupplierWrite body) { return support.saveSupplier(id, body); }
    @GetMapping("/warranties") public AssetSupportService.Warranties warranties(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "") String filter) { return support.warranties(page, filter); }
    @GetMapping("/warranties/{assetId}") public AssetSupportService.Warranty warranty(@PathVariable Long assetId) { return support.getWarranty(assetId); }
    @PutMapping("/warranties/{assetId}") public AssetSupportService.Warranty saveWarranty(@PathVariable Long assetId, @Valid @RequestBody AssetSupportService.WarrantyWrite body) { return support.saveWarranty(assetId, body); }
}
