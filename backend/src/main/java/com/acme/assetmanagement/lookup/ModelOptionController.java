package com.acme.assetmanagement.lookup;

import com.acme.assetmanagement.asset.AssetRepository;
import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Option metadata only. Model names and referenced asset rows are never updated here. */
@RestController
@RequestMapping("/api/foundation/models")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ModelOptionController {
    private final LookupRepository options;
    private final AssetRepository assets;
    private final AuditLogService audit;

    public ModelOptionController(LookupRepository options, AssetRepository assets, AuditLogService audit) {
        this.options = options; this.assets = assets; this.audit = audit;
    }

    public record Option(Long id, String name, boolean disabled, long version, long assetCount) {}
    public record Create(@NotBlank @Size(max = 120) String name) {}
    public record Availability(@NotNull @PositiveOrZero Long version, @NotNull Boolean disabled) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<Option> list() {
        Map<Long, Long> usage = new HashMap<>();
        assets.countModelUsage().forEach(row -> usage.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
        return options.findByTypeOrderByNameAsc(LookupType.MODEL).stream()
                .map(value -> option(value, usage.getOrDefault(value.getId(), 0L))).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Option create(@Valid @RequestBody Create request) {
        String name = request.name().trim();
        if (options.findByTypeAndNameIgnoreCase(LookupType.MODEL, name).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "型号已存在；已停用的型号请直接启用");
        var saved = options.saveAndFlush(new LookupValue(LookupType.MODEL, name));
        audit.success(AuditAction.LOOKUP_CREATE, "LOOKUP", saved.getId(), name, "新建设备型号", Map.of("名称", name));
        return option(saved, 0);
    }

    @PutMapping("/{id}/availability")
    @Transactional
    public Option availability(@PathVariable Long id, @Valid @RequestBody Availability request) {
        var value = options.locked(id).filter(option -> option.getType() == LookupType.MODEL)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "设备型号不存在"));
        if (value.getOptionVersion() != request.version())
            throw new ApiException(HttpStatus.CONFLICT, "型号状态已更新，请刷新后重新操作");
        boolean before = value.isDisabled();
        if (before != request.disabled()) {
            value.setDisabled(request.disabled()); options.saveAndFlush(value);
            audit.success(AuditAction.SETTING_UPDATE, "LOOKUP", id, value.getName(), request.disabled() ? "停用设备型号" : "启用设备型号",
                    Map.of("原状态", before ? "停用" : "启用", "新状态", request.disabled() ? "停用" : "启用"));
        }
        return option(value, assets.countUsingLookup(id));
    }

    private Option option(LookupValue value, long count) {
        return new Option(value.getId(), value.getName(), value.isDisabled(), value.getOptionVersion(), count);
    }
}
