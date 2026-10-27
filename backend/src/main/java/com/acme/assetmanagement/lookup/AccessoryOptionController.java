package com.acme.assetmanagement.lookup;

import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/foundation/accessories")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AccessoryOptionController {
    private final AccessoryOptionRepository repository;
    private final AuditLogService audit;

    public AccessoryOptionController(AccessoryOptionRepository repository, AuditLogService audit) {
        this.repository = repository; this.audit = audit;
    }

    public record Option(Long id, String name, String model, String specification, boolean disabled, long version) {}
    public record Write(@NotBlank @Size(max = 120) String name, @Size(max = 160) String model,
                        @Size(max = 300) String specification, @PositiveOrZero Long version) {}
    public record Availability(@NotNull @PositiveOrZero Long version, @NotNull Boolean disabled) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<Option> list() {
        return repository.findAllByOrderByNameAscModelAscIdAsc().stream().map(this::option).toList();
    }

    @GetMapping("/choices")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAnyAuthority('ASSET_CREATE', 'ASSET_EDIT')")
    @Transactional(readOnly = true)
    public List<Option> choices() {
        return repository.findByDisabledFalseOrderByNameAscModelAscIdAsc().stream().map(this::option).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Option create(@Valid @RequestBody Write request) {
        String name = request.name().trim(), model = clean(request.model()), specification = clean(request.specification());
        requireUnique(null, name, model, specification);
        var value = new AccessoryOption(name, model, specification);
        save(value);
        audit.success(AuditAction.LOOKUP_CREATE, "ACCESSORY_OPTION", value.id, value.name, "新增随附配件目录", fields(value));
        return option(value);
    }

    @PutMapping("/{id}")
    @Transactional
    public Option update(@PathVariable Long id, @Valid @RequestBody Write request) {
        var value = reviewed(id, request.version());
        String name = request.name().trim(), model = clean(request.model()), specification = clean(request.specification());
        requireUnique(id, name, model, specification);
        var before = fields(value);
        value.update(name, model, specification); save(value);
        audit.success(AuditAction.SETTING_UPDATE, "ACCESSORY_OPTION", id, value.name, "修改随附配件目录", audit.diff(before, fields(value)));
        return option(value);
    }

    @PutMapping("/{id}/availability")
    @Transactional
    public Option availability(@PathVariable Long id, @Valid @RequestBody Availability request) {
        var value = reviewed(id, request.version());
        if (value.disabled != request.disabled()) {
            value.disabled = request.disabled(); save(value);
            audit.success(AuditAction.SETTING_UPDATE, "ACCESSORY_OPTION", id, value.name,
                    value.disabled ? "停用随附配件目录" : "启用随附配件目录", Map.of("状态", value.disabled ? "停用" : "启用"));
        }
        return option(value);
    }

    private AccessoryOption reviewed(Long id, Long version) {
        if (version == null) throw new ApiException(HttpStatus.BAD_REQUEST, "请提供配件目录版本");
        var value = repository.locked(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "配件目录不存在"));
        if (value.version != version) throw new ApiException(HttpStatus.CONFLICT, "配件目录已更新，请刷新后重新操作");
        return value;
    }

    private void requireUnique(Long id, String name, String model, String specification) {
        if (repository.findByNameIgnoreCaseAndModelIgnoreCaseAndSpecificationIgnoreCase(name, model, specification)
                .filter(value -> !Objects.equals(value.id, id)).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "相同配件已存在；已停用的配件请直接启用");
    }

    private void save(AccessoryOption value) {
        try { repository.saveAndFlush(value); }
        catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "相同配件已存在，请刷新后重试");
        }
    }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private Map<String, String> fields(AccessoryOption value) { return Map.of("名称", value.name, "型号", value.model, "规格", value.specification); }
    private Option option(AccessoryOption value) { return new Option(value.id, value.name, value.model, value.specification, value.disabled, value.version); }
}
