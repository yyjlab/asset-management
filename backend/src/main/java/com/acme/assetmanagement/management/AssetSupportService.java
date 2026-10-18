package com.acme.assetmanagement.management;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.user.UserRepository;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @Transactional(readOnly = true)
public class AssetSupportService {
    private final SupplierRepository suppliers;
    private final WarrantyRepository warranties;
    private final AssetRepository assets;
    private final ManagementAccess access;
    private final UserRepository users;
    public AssetSupportService(SupplierRepository suppliers, WarrantyRepository warranties, AssetRepository assets, ManagementAccess access, UserRepository users) {
        this.suppliers = suppliers; this.warranties = warranties; this.assets = assets; this.access = access; this.users = users;
    }
    public record SupplierWrite(@NotBlank @Size(max = 36) String requestKey, @Min(0) Long version, @NotBlank @Size(max = 120) String name,
            @Size(max = 80) String contact, @Size(max = 40) String phone, @Email @Size(max = 150) String email, @Size(max = 1000) String notes, @NotNull Boolean active) {}
    public record SupplierView(Long id, long version, String name, String contact, String phone, String email, String notes, boolean active) {}
    public record Suppliers(List<SupplierView> items, int page, long totalElements, int totalPages) {}
    public record WarrantyWrite(@Min(0) Long version, @Positive Long supplierId, @PastOrPresent LocalDate purchaseDate,
            @NotNull LocalDate warrantyEnd, @Size(max = 1000) String notes) {}
    public record Event(LocalDateTime occurredAt, String actor, String description) {}
    public record Warranty(Long assetId, long version, String assetTag, Long supplierId, String supplierName, LocalDate purchaseDate,
            LocalDate warrantyEnd, String notes, boolean expired, boolean dueSoon, List<Event> events) {}
    public record Warranties(List<Warranty> items, int page, long totalElements, int totalPages) {}
    public Suppliers suppliers(int page) {
        access.manager(); var result = suppliers.findAll(page(page)); return new Suppliers(result.stream().map(this::supplier).toList(), result.getNumber(), result.getTotalElements(), result.getTotalPages());
    }
    public SupplierView supplier(Long id) { access.manager(); return supplier(suppliers.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "供应商不存在"))); }
    @Transactional public SupplierView saveSupplier(Long id, SupplierWrite request) {
        var actor = access.manager(); String key = ManagementAccess.key(request.requestKey()); users.findForWorkflowUpdate(actor.getId()).orElseThrow();
        var s = id == null ? suppliers.findByRequestKey(key).orElseGet(Supplier::new) : suppliers.locked(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "供应商不存在"));
        if (id == null && s.id != null) return supplier(s);
        if (id != null && (request.version() == null || request.version() != s.version)) throw error(HttpStatus.CONFLICT, "供应商已更新，请刷新");
        if (s.id == null) s.requestKey = key;
        s.name = request.name().trim(); s.contact = ManagementAccess.clean(request.contact()); s.phone = ManagementAccess.clean(request.phone());
        s.email = ManagementAccess.clean(request.email()); s.notes = ManagementAccess.clean(request.notes()); s.active = request.active(); s.updatedAt = LocalDateTime.now();
        suppliers.saveAndFlush(s); return supplier(s);
    }
    public Warranties warranties(int page, String filter) {
        access.manager(); var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Specification<AssetWarranty> spec = switch (filter) {
            case "" -> (r, q, cb) -> cb.conjunction();
            case "due" -> (r, q, cb) -> cb.between(r.get("warrantyEnd"), today, today.plusDays(30));
            case "expired" -> (r, q, cb) -> cb.lessThan(r.get("warrantyEnd"), today);
            default -> throw error(HttpStatus.BAD_REQUEST, "保修筛选无效");
        };
        var result = warranties.findAll(spec, page(page)); return new Warranties(result.stream().map(this::warranty).toList(), result.getNumber(), result.getTotalElements(), result.getTotalPages());
    }
    public Warranty getWarranty(Long assetId) { access.manager(); return warranty(warranties.findByAssetId(assetId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "此设备尚未登记保修"))); }
    @Transactional public Warranty saveWarranty(Long assetId, WarrantyWrite request) {
        var actor = access.manager(); var a = assets.findForRegistrationLink(assetId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "资产不存在"));
        var w = warranties.lockedAsset(assetId).orElseGet(AssetWarranty::new);
        if (w.id != null && (request.version() == null || request.version() != w.version) || w.id == null && request.version() != null)
            throw error(HttpStatus.CONFLICT, "保修记录已变化，请刷新");
        if (request.purchaseDate() != null && request.warrantyEnd().isBefore(request.purchaseDate())) throw error(HttpStatus.BAD_REQUEST, "保修截止日期不能早于购买日期");
        Supplier supplier = request.supplierId() == null ? null : suppliers.findById(request.supplierId()).orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "供应商不存在"));
        if (supplier != null && !supplier.active && !Objects.equals(w.supplierId, supplier.id)) throw error(HttpStatus.CONFLICT, "此供应商已停用，不能新增关联");
        String before = w.id == null ? "首次登记" : "原截止日期：" + w.warrantyEnd + "，原供应商：" + Objects.toString(w.supplierName, "未填写");
        w.assetId = a.getId(); w.assetTag = a.getAssetTag(); w.supplierId = supplier == null ? null : supplier.id; w.supplierName = supplier == null ? "" : supplier.name;
        w.purchaseDate = request.purchaseDate(); w.warrantyEnd = request.warrantyEnd(); w.notes = ManagementAccess.clean(request.notes()); w.updatedAt = LocalDateTime.now();
        w.events.add(new AssetWarranty.Event(actor.getDisplayName(), before + "；登记购买日期：" + w.purchaseDate + "，保修截止：" + w.warrantyEnd + "，供应商：" + w.supplierName));
        warranties.saveAndFlush(w); return warranty(w);
    }
    private SupplierView supplier(Supplier s) { return new SupplierView(s.id, s.version, s.name, s.contact, s.phone, s.email, s.notes, s.active); }
    private Warranty warranty(AssetWarranty w) {
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai")); return new Warranty(w.assetId, w.version, w.assetTag, w.supplierId, w.supplierName, w.purchaseDate, w.warrantyEnd, w.notes,
                w.warrantyEnd.isBefore(today), !w.warrantyEnd.isBefore(today) && !w.warrantyEnd.isAfter(today.plusDays(30)), w.events.stream().map(e -> new Event(e.occurredAt, e.actor, e.description)).toList());
    }
    private static PageRequest page(int page) { return PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "id")); }
    private static ApiException error(HttpStatus status, String message) { return new ApiException(status, message); }
}
