package com.acme.assetmanagement.management;

import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.user.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service @Transactional(readOnly = true)
public class EquipmentKitService {
    private final EquipmentKitRepository kits;
    private final ManagementAccess access;
    private final UserRepository users;
    public EquipmentKitService(EquipmentKitRepository kits, ManagementAccess access, UserRepository users) { this.kits = kits; this.access = access; this.users = users; }
    public record KitItem(@NotBlank @Size(max = 120) String name, @NotNull @DecimalMin("1") @DecimalMax("50") @Digits(integer = 2, fraction = 0) BigDecimal quantity) {}
    public record Write(@NotBlank @Size(max = 36) String requestKey, @Min(0) Long version, @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String notes, @NotNull Boolean active, @NotEmpty @Size(max = 50) List<@NotNull @Valid KitItem> items) {}
    public record Item(String name, int quantity) {}
    public record Detail(Long id, long version, String name, String notes, boolean active, List<Item> items, List<String> manualItems) {}
    public record Page(List<Detail> items, int page, long totalElements, int totalPages) {}
    public Page list(int page) {
        access.manager(); var result = kits.findAll(PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "id")));
        return new Page(result.stream().map(this::detail).toList(), result.getNumber(), result.getTotalElements(), result.getTotalPages());
    }
    public Detail get(Long id) { access.manager(); return detail(kits.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "配发模板不存在"))); }
    @Transactional public Detail save(Long id, Write request) {
        var actor = access.manager(); String key = ManagementAccess.key(request.requestKey());
        users.findForWorkflowUpdate(actor.getId()).orElseThrow();
        var kit = id == null ? kits.findByRequestKey(key).orElseGet(EquipmentKit::new) : kits.locked(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "配发模板不存在"));
        if (id == null && kit.id != null) return detail(kit);
        if (id != null && (request.version() == null || request.version() != kit.version)) throw error(HttpStatus.CONFLICT, "模板已更新，请刷新");
        if (request.items().stream().mapToInt(i -> i.quantity().intValueExact()).sum() > 50) throw error(HttpStatus.BAD_REQUEST, "每套模板最多50件，便于逐件交接确认");
        if (kit.id == null) kit.requestKey = key;
        kit.name = request.name().trim(); kit.notes = ManagementAccess.clean(request.notes()); kit.active = request.active(); kit.items.clear();
        request.items().forEach(i -> kit.items.add(new EquipmentKit.Item(i.name().trim(), i.quantity().intValueExact())));
        kit.updatedAt = LocalDateTime.now(); kits.saveAndFlush(kit); return detail(kit);
    }
    private Detail detail(EquipmentKit k) {
        var manual = new ArrayList<String>(); for (var i : k.items) for (int n = 1; n <= i.quantity; n++) manual.add(i.name + (i.quantity > 1 ? "（" + n + "/" + i.quantity + "）" : ""));
        return new Detail(k.id, k.version, k.name, k.notes, k.active, k.items.stream().map(i -> new Item(i.name, i.quantity)).toList(), manual);
    }
    private static ApiException error(HttpStatus status, String message) { return new ApiException(status, message); }
}
