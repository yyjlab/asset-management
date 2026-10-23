package com.acme.assetmanagement.setting;

import com.acme.assetmanagement.asset.DomainJoinStatus;
import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import com.acme.assetmanagement.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/foundation/domain-statuses")
public class DomainStatusController {
    private final DomainStatusPreferenceRepository repository;
    private final AuditLogService audit;

    public DomainStatusController(DomainStatusPreferenceRepository repository, AuditLogService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<Option> list() {
        var saved = new EnumMap<DomainJoinStatus, DomainStatusPreference>(DomainJoinStatus.class);
        repository.findAll().forEach(value -> saved.put(value.getCode(), value));
        return Arrays.stream(DomainJoinStatus.values()).map(code -> option(code, saved.get(code)))
                .sorted(Comparator.comparingInt(Option::sortOrder).thenComparing(Option::code)).toList();
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public Option update(@PathVariable String code, @Valid @RequestBody Update request) {
        DomainJoinStatus status;
        try { status = DomainJoinStatus.valueOf(code); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "无效的入域状态"); }
        return updateOption(status, request);
    }

    private Option updateOption(DomainJoinStatus code, Update request) {
        var preference = repository.findById(code).orElseGet(() -> new DomainStatusPreference(code));
        if (!Objects.equals(preference.getVersion(), request.version())) {
            throw new ApiException(HttpStatus.CONFLICT, "选项已被更新，请刷新后重新编辑");
        }
        var before = option(code, preference.getVersion() == null ? null : preference);
        preference.update(request.description().trim(), request.sortOrder());
        try {
            repository.saveAndFlush(preference);
        } catch (org.springframework.dao.DataIntegrityViolationException | org.springframework.dao.OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "选项已被更新，请刷新后重新编辑");
        }
        var after = option(code, preference);
        audit.success(AuditAction.SETTING_UPDATE, "DOMAIN_STATUS", null, code.label(), "修改入域状态选项说明与排序",
                audit.diff(Map.of("说明", before.description(), "排序", before.sortOrder()),
                        Map.of("说明", after.description(), "排序", after.sortOrder())));
        return after;
    }

    private Option option(DomainJoinStatus code, DomainStatusPreference preference) {
        return new Option(code, code.label(), preference == null ? code.description() : preference.getDescription(),
                preference == null ? code.sortOrder() : preference.getSortOrder(), preference == null ? null : preference.getVersion());
    }

    public record Option(DomainJoinStatus code, String label, String description, int sortOrder, Long version) {}
    public record Update(@NotNull @Size(max = 200) String description, @NotNull @Min(0) @Max(999) Integer sortOrder, @PositiveOrZero Long version) {}
}
