package com.acme.assetmanagement.asset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/accessory-stock")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AccessoryStockController {
    private final AccessoryStockService service;
    public AccessoryStockController(AccessoryStockService service){this.service=service;}
    public record Create(@NotBlank @Size(max=120) String type,@NotBlank @Size(max=160) String brand,@NotNull Long companyId,@NotNull Long locationId,@Min(1) @Max(9999999) int quantity){}
    public record Change(@NotNull @Min(0) Long version,@Min(1) @Max(9999999) int quantity,boolean scrap,@Size(max=500) String note){}
    @GetMapping public List<AccessoryStockService.Row> list(){return service.list(null,null);}
    @GetMapping("/choices")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAnyAuthority('ASSET_CREATE','ASSET_EDIT')")
    public List<AccessoryStockService.Row> choices(@RequestParam Long companyId,@RequestParam Long locationId){return service.list(companyId,locationId);}
    @PostMapping public AccessoryStockService.Row create(@Valid @RequestBody Create r){return service.create(r.type(),r.brand(),r.companyId(),r.locationId(),r.quantity());}
    @PostMapping("/{id}/adjust") public AccessoryStockService.Row adjust(@PathVariable Long id,@Valid @RequestBody Change r){return service.adjust(id,r.version(),r.quantity(),r.scrap(),r.note());}
    @GetMapping("/{id}") public AccessoryStockService.Details details(@PathVariable Long id){return service.details(id);}
}
