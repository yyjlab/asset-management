package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.lookup.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class AssetBundleService {
    private final AssetService service;
    private final AssetRepository assets;
    private final AssetAccess access;
    private final LookupRepository lookups;
    private final AssetTagSequenceRepository sequences;
    private final AssetBundleSubmissionRepository submissions;
    private final ObjectMapper json;
    private final AuditLogService audit;

    public AssetBundleService(AssetService service, AssetRepository assets, AssetAccess access, LookupRepository lookups,
                              AssetTagSequenceRepository sequences, AssetBundleSubmissionRepository submissions,
                              ObjectMapper json, AuditLogService audit) {
        this.service = service; this.assets = assets; this.access = access; this.lookups = lookups;
        this.sequences = sequences; this.submissions = submissions; this.json = json; this.audit = audit;
    }
    public record Result(AssetResponse computer, List<AssetResponse> displays, boolean replayed) {}

    public Result save(AssetBundleRequest request) {
        var scope = access.scope(); String fingerprint = fingerprint(request);
        // The allocator lock already protects image writes and sequential IDs. Hold it through the outer commit,
        // including the completed-key record, so concurrent retries see either all records or none.
        sequences.findLockedByDate(AssetTagGenerator.ALLOCATION_LOCK_DATE).orElseThrow();
        var previous = submissions.findById(request.requestKey()).orElse(null);
        if (previous != null) {
            if (!previous.ownerId.equals(scope.userId()) || !previous.fingerprint.equals(fingerprint))
                throw new ApiException(HttpStatus.CONFLICT, "此整套提交已完成，不能用同一提交标识保存不同内容；请先查询上次保存结果");
            return result(previous, true);
        }
        validate(request, scope);
        var computer = request.computerId() == null ? service.create(request.computer(), request.cloneSourceId())
                : service.update(request.computerId(), request.computer(), request.computerVersion().toString());
        var displayIds = new ArrayList<>(request.existingDisplays().stream().map(AssetBundleRequest.ExistingDisplay::id).toList());
        for (var display : request.displays()) {
            var created = service.create(display.withBindings(List.of(), computer.id()), null);
            displayIds.add(created.id());
        }
        var saved = new AssetBundleSubmission(); saved.requestKey = request.requestKey(); saved.ownerId = scope.userId();
        saved.fingerprint = fingerprint; saved.computerId = computer.id(); saved.displayIds.addAll(displayIds);
        submissions.saveAndFlush(saved);
        audit.success(AuditAction.ASSET_CREATE, "ASSET_BUNDLE", computer.id(), computer.assetTag(), "整套保存电脑与显示器",
                Map.of("电脑", computer.assetTag(), "显示器数量", displayIds.size(), "新建显示器数量", request.displays().size()));
        return result(saved, false);
    }

    @Transactional(readOnly = true)
    public Result get(String requestKey) {
        var scope = access.scope();
        var saved = submissions.findById(requestKey).filter(row -> row.ownerId.equals(scope.userId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "未查到已完成的整套提交"));
        return result(saved, true);
    }

    private Result result(AssetBundleSubmission saved, boolean replayed) {
        // Recheck current access and existence; never recreate deleted or newly inaccessible assets on retry.
        return new Result(service.get(saved.computerId), saved.displayIds.stream().map(service::get).toList(), replayed);
    }
    private void validate(AssetBundleRequest request, AssetAccess.Scope scope) {
        if (request.computerId() != null && (request.computerVersion() == null || request.cloneSourceId() != null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "编辑电脑须提供原版本，且不能同时克隆");
        if (request.computerId() == null && request.computerVersion() != null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "新电脑不能带有旧版本");
        requireProfile(request.computer(), AssetProfile.COMPUTER);
        if (request.computer().boundComputerId() != null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "整套录入的主体必须是电脑");
        var selected = request.computer().boundDisplayIds() == null ? List.<Long>of() : request.computer().boundDisplayIds();
        var reviewed = request.existingDisplays().stream().map(AssetBundleRequest.ExistingDisplay::id).toList();
        if (reviewed.size() + request.displays().size() > 20)
            throw new ApiException(HttpStatus.BAD_REQUEST, "每套最多包含20台显示器");
        if (new HashSet<>(reviewed).size() != reviewed.size() || new HashSet<>(selected).size() != selected.size()
                || !new HashSet<>(selected).equals(new HashSet<>(reviewed)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "已有显示器须逐台提供所查看的版本，不能重复选择");
        if (reviewed.isEmpty() && request.displays().isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "整套录入至少包含一台显示器");
        for (var ref : request.existingDisplays()) {
            var display = assets.findById(ref.id()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "显示器不存在或无权访问"));
            access.require(scope, display);
            if (display.getVersion() != ref.version()) throw new ApiException(HttpStatus.CONFLICT, "显示器信息已更新，请刷新后重新选择");
            if (profile(display.getCategory()) != AssetProfile.DISPLAY)
                throw new ApiException(HttpStatus.BAD_REQUEST, "只能选择显示器资产");
            if (display.getBoundComputers().stream().anyMatch(value -> !Objects.equals(value.getId(), request.computerId())))
                throw new ApiException(HttpStatus.CONFLICT, "显示器已绑定其他电脑，请核对后再操作");
        }
        for (var display : request.displays()) {
            requireProfile(display, AssetProfile.DISPLAY);
            if (display.assetTag() != null && !display.assetTag().isBlank() || display.boundComputerId() != null
                    || display.boundDisplayIds() != null && !display.boundDisplayIds().isEmpty())
                throw new ApiException(HttpStatus.BAD_REQUEST, "新显示器须自动编号且不能预先绑定其他设备");
        }
    }
    private void requireProfile(AssetRequest request, AssetProfile expected) {
        var category = lookups.findById(request.categoryId()).filter(value -> value.getType() == LookupType.CATEGORY)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "资产分类不存在"));
        if (profile(category) != expected) throw new ApiException(HttpStatus.BAD_REQUEST, "电脑和显示器必须选择各自的资产分类");
    }
    private AssetProfile profile(LookupValue category) {
        return category.getAssetProfile() == null ? AssetProfile.infer(category.getName()) : category.getAssetProfile();
    }
    private String fingerprint(AssetBundleRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
