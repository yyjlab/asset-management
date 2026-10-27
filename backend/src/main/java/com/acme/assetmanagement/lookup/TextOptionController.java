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

/** Manage suggestions only; stored asset names and CPU text are never rewritten here. */
@RestController
@RequestMapping("/api/foundation/{type:cpus|asset-names}")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TextOptionController {
    private final LookupRepository options;
    private final AssetRepository assets;
    private final AuditLogService audit;

    public TextOptionController(LookupRepository options, AssetRepository assets, AuditLogService audit) {
        this.options = options; this.assets = assets; this.audit = audit;
    }
    public record Option(Long id, String name, boolean disabled, long version, long assetCount) {}
    public record Create(@NotBlank @Size(max = 200) String name) {}
    public record Availability(@NotNull @PositiveOrZero Long version, @NotNull Boolean disabled) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<Option> list(@PathVariable String type) {
        var kind = kind(type); var usage = usage(kind);
        return options.findByTypeOrderByNameAsc(kind).stream().map(value -> option(value, usage)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Option create(@PathVariable String type, @Valid @RequestBody Create request) {
        var kind = kind(type); String name = request.name().trim();
        if (kind == LookupType.ASSET_NAME && name.length() > 160)
            throw new ApiException(HttpStatus.BAD_REQUEST, "资产名称不能超过160个字符");
        if (options.findByTypeAndNameIgnoreCase(kind, name).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, kind.getLabel() + "已存在；已停用的选项请直接启用");
        LookupValue saved;
        try { saved = options.saveAndFlush(new LookupValue(kind, name)); }
        catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, kind.getLabel() + "已存在，请刷新后重试");
        }
        audit.success(AuditAction.LOOKUP_CREATE, "LOOKUP", saved.getId(), name, "新增" + kind.getLabel(), Map.of("名称", name));
        return option(saved, usage(kind));
    }

    @PutMapping("/{id}/availability")
    @Transactional
    public Option availability(@PathVariable String type, @PathVariable Long id, @Valid @RequestBody Availability request) {
        var kind = kind(type);
        var value = options.locked(id).filter(row -> row.getType() == kind)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, kind.getLabel() + "选项不存在"));
        if (value.getOptionVersion() != request.version())
            throw new ApiException(HttpStatus.CONFLICT, "选项已更新，请刷新后重新操作");
        boolean before = value.isDisabled();
        if (before != request.disabled()) {
            value.setDisabled(request.disabled()); options.saveAndFlush(value);
            audit.success(AuditAction.SETTING_UPDATE, "LOOKUP", id, value.getName(), (value.isDisabled() ? "停用" : "启用") + kind.getLabel(),
                    Map.of("原状态", before ? "停用" : "启用", "新状态", value.isDisabled() ? "停用" : "启用"));
        }
        return option(value, usage(kind));
    }

    private LookupType kind(String type) {
        return switch (type) {
            case "cpus" -> LookupType.CPU;
            case "asset-names" -> LookupType.ASSET_NAME;
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "选项分类不存在");
        };
    }
    private Map<String, Long> usage(LookupType kind) {
        var counts = new HashMap<String, Long>();
        var rows = kind == LookupType.CPU ? assets.countCpuUsage() : assets.countNameUsage();
        rows.forEach(row -> counts.put(normalized((String) row[0]), ((Number) row[1]).longValue()));
        return counts;
    }
    private String normalized(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private Option option(LookupValue value, Map<String, Long> usage) {
        return new Option(value.getId(), value.getName(), value.isDisabled(), value.getOptionVersion(), usage.getOrDefault(normalized(value.getName()), 0L));
    }
}
