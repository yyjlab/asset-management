package com.acme.assetmanagement.lookup;

import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.asset.AssetRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/lookups")
public class LookupController {
    private final LookupRepository repository;
    private final AssetRepository assetRepository;
    private final AuditLogService auditLogService;

    public LookupController(LookupRepository repository, AssetRepository assetRepository, AuditLogService auditLogService) {
        this.repository = repository;
        this.assetRepository = assetRepository;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('ASSET_VIEW')")
    public List<LookupResponse> list(@RequestParam(required = false) LookupType type) {
        List<LookupValue> values = type == null
                ? repository.findAllByOrderByTypeAscNameAsc()
                : repository.findByTypeOrderByNameAsc(type);
        return values.stream().filter(value -> value.getType() != LookupType.MODEL || !value.isDisabled())
                .map(LookupResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ASSET_CREATE', 'ASSET_EDIT')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LookupResponse create(@Valid @RequestBody LookupRequest request) {
        if (request.type() == LookupType.STATUS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "资产状态是系统固定选项，不能新建");
        }
        String name = request.name().trim();
        repository.findByTypeAndNameIgnoreCase(request.type(), name).ifPresent(value -> {
            throw new ApiException(HttpStatus.CONFLICT, request.type().getLabel() + "“" + name + "”已存在");
        });
        LookupValue saved = repository.save(new LookupValue(request.type(), name, request.assetProfile()));
        auditLogService.success(AuditAction.LOOKUP_CREATE, "LOOKUP", saved.getId(), saved.getType().getLabel() + " · " + saved.getName(),
                "新建" + saved.getType().getLabel() + "选项“" + saved.getName() + "”", java.util.Map.of("名称", saved.getName()));
        return LookupResponse.from(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ASSET_CREATE', 'ASSET_EDIT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id) {
        LookupValue value = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "选项不存在或已被删除"));
        if (value.getType() == LookupType.STATUS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "资产状态是系统固定选项，不能删除");
        }
        long usageCount = assetRepository.countUsingLookup(id);
        if (usageCount > 0) {
            throw new ApiException(HttpStatus.CONFLICT, value.getType().getLabel() + "“" + value.getName()
                    + "”正在被 " + usageCount + " 项资产使用，不能删除");
        }
        repository.delete(value);
        auditLogService.success(AuditAction.LOOKUP_DELETE, "LOOKUP", id, value.getType().getLabel() + " · " + value.getName(),
                "删除" + value.getType().getLabel() + "选项“" + value.getName() + "”", java.util.Map.of("名称", value.getName()));
    }

    public record LookupRequest(
            @NotNull(message = "字典类型不能为空") LookupType type,
            @NotBlank(message = "名称不能为空") @Size(max = 200, message = "名称不能超过200个字符") String name,
            AssetProfile assetProfile
    ) {}

    public record LookupResponse(Long id, LookupType type, String typeLabel, String name, AssetProfile assetProfile, List<String> parameterTemplate) {
        public static LookupResponse from(LookupValue value) {
            return new LookupResponse(value.getId(), value.getType(), value.getType().getLabel(), value.getName(), value.getAssetProfile(), List.copyOf(value.getParameterTemplate()));
        }
    }
}
