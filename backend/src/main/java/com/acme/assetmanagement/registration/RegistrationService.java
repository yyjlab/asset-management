package com.acme.assetmanagement.registration;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.user.*;
import com.acme.assetmanagement.workflow.WorkflowAccess;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class RegistrationService {
    private static final Set<String> OPEN = Set.of("SUBMITTED", "NEEDS_INFO");
    private final RegistrationRepository registrations;
    private final RegistrationPhotoRepository photos;
    private final WorkflowAccess access;
    private final UserRepository users;
    private final AssetRepository assets;
    private final AssetService assetService;
    private final AssetImageStorageService images;
    private final LookupRepository lookups;
    private final com.acme.assetmanagement.workflow.TaskNotifications notifications;
    public RegistrationService(RegistrationRepository registrations, RegistrationPhotoRepository photos, WorkflowAccess access,
            UserRepository users, AssetRepository assets, AssetService assetService, AssetImageStorageService images, LookupRepository lookups,
            com.acme.assetmanagement.workflow.TaskNotifications notifications) {
        this.registrations = registrations; this.photos = photos; this.access = access; this.users = users;
        this.assets = assets; this.assetService = assetService; this.images = images; this.lookups = lookups;
        this.notifications = notifications;
    }
    public record Summary(Long id, long version, Long ownerId, String ownerName, String name, String model,
                          String serialNumber, String state, LocalDateTime updatedAt, Long approvedAssetId, String approvedAssetTag) {}
    public record Page(List<Summary> items, int page, long totalElements, int totalPages, boolean canReview) {}
    public record Event(LocalDateTime occurredAt, String actorName, String description) {}
    public record Detail(Summary request, String requestKey, String department, String location, String cpu, String memory, String storage,
                         String notes, String reviewNote, String reviewerName, List<String> photos,
                         List<Event> events, boolean serialAlreadyInAssets) {}
    public record Photo(byte[] bytes, String mediaType) {}

    public Page list(int page, String state, boolean review) {
        var actor = review ? access.reviewer() : access.current();
        if (!state.isBlank() && !Set.of("SUBMITTED", "NEEDS_INFO", "APPROVED", "REJECTED", "WITHDRAWN").contains(state))
            throw error(HttpStatus.BAD_REQUEST, "登记状态筛选无效");
        Specification<AssetRegistration> spec = (root, query, cb) -> review ? cb.conjunction() : cb.equal(root.get("ownerId"), actor.getId());
        if (!state.isBlank()) spec = spec.and((root, query, cb) -> cb.equal(root.get("state"), state));
        var result = registrations.findAll(spec, PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "updatedAt", "id")));
        return new Page(result.map(this::summary).getContent(), result.getNumber(), result.getTotalElements(), result.getTotalPages(), access.canReview(actor));
    }
    public Detail get(Long id) { return detail(visible(id, access.current())); }
    public Photo photo(Long id) {
        var metadata = photos.findInfoById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "登记照片不存在"));
        visible(metadata.getRegistrationId(), access.current());
        var photo = photos.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "登记照片不存在"));
        return new Photo(photo.data, photo.mediaType);
    }

    @Transactional
    public Detail submit(RegistrationRequest.Submit request, List<MultipartFile> files) {
        var actor = access.current();
        users.findForWorkflowUpdate(actor.getId()).orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "账号不可用"));
        try { UUID.fromString(request.requestKey()); } catch (IllegalArgumentException exception) { throw error(HttpStatus.BAD_REQUEST, "登记请求标识无效"); }
        var existing = registrations.findByRequestKey(request.requestKey());
        if (existing.isPresent()) {
            if (!existing.get().ownerId.equals(actor.getId())) throw error(HttpStatus.NOT_FOUND, "登记请求不存在");
            return detail(existing.get());
        }
        if (registrations.countByOwnerIdAndCreatedAtAfter(actor.getId(), LocalDateTime.now().minusDays(1)) >= 20)
            throw error(HttpStatus.TOO_MANY_REQUESTS, "每个账号24小时内最多提交20项设备登记，请联系管理员");
        var registration = new AssetRegistration(); registration.requestKey = request.requestKey();
        registration.ownerId = actor.getId(); registration.ownerName = actor.getDisplayName();
        apply(registration, request); duplicateOpen(registration);
        var prepared = prepare(files);
        requireEvidence(registration, prepared.size());
        event(registration, actor, "提交设备登记；尚未写入正式资产台账");
        registrations.saveAndFlush(registration); savePhotos(registration, prepared); notifyChange(registration);
        return detail(registration);
    }

    @Transactional
    public Detail supplement(Long id, RegistrationRequest.Submit request, List<MultipartFile> files) {
        var actor = access.current();
        users.findForWorkflowUpdate(actor.getId()).orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "账号不可用"));
        var registration = locked(id); owned(registration, actor); version(registration, request.version());
        if (!"NEEDS_INFO".equals(registration.state)) throw error(HttpStatus.CONFLICT, "只有待补充资料的登记可以重新提交");
        if (!registration.requestKey.equals(request.requestKey())) throw error(HttpStatus.BAD_REQUEST, "不能更改原登记请求标识");
        apply(registration, request); duplicateOpen(registration);
        var prepared = prepare(files);
        int count = prepared.isEmpty() ? photos.findInfoByRegistrationIdAndRevisionOrderById(id, registration.photoRevision).size() : prepared.size();
        requireEvidence(registration, count);
        if (!prepared.isEmpty()) { registration.photoRevision++; savePhotos(registration, prepared); }
        registration.state = "SUBMITTED"; event(registration, actor, "补充资料后重新提交，原审核记录与照片保留");
        registrations.saveAndFlush(registration); notifyChange(registration); return detail(registration);
    }

    @Transactional
    public Detail withdraw(Long id, RegistrationRequest.Withdraw request) {
        var actor = access.current(); var registration = locked(id); owned(registration, actor); version(registration, request.version()); open(registration);
        registration.state = "WITHDRAWN"; event(registration, actor, "撤回登记：" + request.note().trim());
        registrations.saveAndFlush(registration); notifyChange(registration); return detail(registration);
    }

    @Transactional
    public Detail review(Long id, RegistrationRequest.Review request) {
        var actor = access.reviewer(); var registration = locked(id); version(registration, request.version()); open(registration);
        registration.state = request.decision(); registration.reviewNote = request.note().trim(); registration.reviewerName = actor.getDisplayName();
        event(registration, actor, ("NEEDS_INFO".equals(request.decision()) ? "要求补充资料：" : "驳回登记：") + registration.reviewNote);
        registrations.saveAndFlush(registration); notifyChange(registration); return detail(registration);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Detail approveCreate(Long id, RegistrationRequest.ApproveCreate request) {
        var actor = access.reviewer(); var registration = locked(id); version(registration, request.version()); submitted(registration);
        var owner = users.findById(registration.ownerId).filter(UserAccount::isEnabled)
                .orElseThrow(() -> error(HttpStatus.CONFLICT, "登记员工账号已停用，不能新增领用资产"));
        var status = lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "已经领出")
                .or(() -> lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用"))
                .orElseThrow(() -> error(HttpStatus.CONFLICT, "请先在设置中准备“已经领出”状态"));
        var photoData = photos.findByRegistrationIdAndRevisionOrderById(id, registration.photoRevision).stream()
                .map(p -> "data:" + p.mediaType + ";base64," + Base64.getEncoder().encodeToString(p.data)).toList();
        var assetRequest = new AssetRequest(null, registration.name, registration.department, registration.cpu, registration.memory,
                registration.storage, null, blankToNull(registration.serialNumber), null, null, null, null,
                request.companyId(), null, registration.model, request.categoryId(), status.getId(), request.locationId(),
                null, null, true, owner.getDisplayName(), null, photoData, registration.notes,
                List.of(), null, List.of(), List.of(), List.of(), owner.getId());
        var asset = assetService.create(assetRequest);
        approve(registration, actor, asset.id(), asset.assetTag(), request.note(), "审核通过并新增资产；未覆盖任何旧资产");
        return detail(registration);
    }

    @Transactional
    public Detail approveExisting(Long id, RegistrationRequest.ApproveExisting request) {
        var actor = access.reviewer(); var registration = locked(id); version(registration, request.version()); submitted(registration);
        var asset = assets.findForRegistrationLink(request.assetId()).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "所选资产不存在"));
        if (asset.getVersion() != request.assetVersion()) throw error(HttpStatus.CONFLICT, "所选资产已变更，请刷新后重新核对");
        if (asset.getAssignedUserId() != null && !asset.getAssignedUserId().equals(registration.ownerId))
            throw error(HttpStatus.CONFLICT, "所选资产关联其他员工，不能作为本人的登记结果");
        if (!clean(registration.serialNumber).isBlank() && !clean(asset.getManufacturerSerialNumber()).isBlank()
                && !serialKey(registration.serialNumber).equals(serialKey(asset.getManufacturerSerialNumber())))
            throw error(HttpStatus.CONFLICT, "登记与台账的SN不一致，请要求员工补充或重新核对");
        approve(registration, actor, asset.getId(), asset.getAssetTag(), request.note(), "审核通过并记录已有资产关联；旧台账字段未修改");
        return detail(registration);
    }

    private void approve(AssetRegistration registration, UserAccount actor, Long assetId, String assetTag, String note, String description) {
        registration.state = "APPROVED"; registration.approvedAssetId = assetId; registration.approvedAssetTag = assetTag;
        registration.approvedSerialKey = registration.serialKey; registration.reviewNote = clean(note); registration.reviewerName = actor.getDisplayName();
        event(registration, actor, description + "，资产编号：" + assetTag);
        registrations.saveAndFlush(registration);
        notifyChange(registration);
    }
    private void notifyChange(AssetRegistration registration) { notifications.registration(registration.id, registration.version, registration.ownerName, registration.state); }
    private void duplicateOpen(AssetRegistration registration) {
        if (registration.serialKey != null && registrations.existsByOwnerIdAndSerialKeyAndStateInAndIdNot(
                registration.ownerId, registration.serialKey, OPEN, registration.id == null ? 0L : registration.id))
            throw error(HttpStatus.CONFLICT, "你已有相同SN的待处理登记，请勿重复提交");
    }
    private void apply(AssetRegistration target, RegistrationRequest.Submit source) {
        target.name = source.name().trim(); target.model = source.model().trim(); target.serialNumber = clean(source.serialNumber());
        target.serialKey = target.serialNumber.isBlank() ? null : serialKey(target.serialNumber);
        target.department = clean(source.department()); target.location = clean(source.location()); target.cpu = clean(source.cpu());
        target.memory = clean(source.memory()); target.storage = clean(source.storage()); target.notes = clean(source.notes());
    }
    private record PreparedPhoto(byte[] bytes, String mediaType) {}
    private List<PreparedPhoto> prepare(List<MultipartFile> files) {
        if (files == null) return List.of();
        if (files.size() > 2) throw error(HttpStatus.BAD_REQUEST, "登记照片最多2张，每张不超过2MB");
        List<PreparedPhoto> prepared = new ArrayList<>();
        for (var file : files) {
            if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024) throw error(HttpStatus.BAD_REQUEST, "登记照片不能为空且每张不超过2MB");
            String type = clean(file.getContentType()).toLowerCase(Locale.ROOT);
            if (!Set.of("image/jpeg", "image/png", "image/webp").contains(type)) throw error(HttpStatus.BAD_REQUEST, "照片只支持JPG、PNG或WebP");
            try {
                byte[] bytes = file.getBytes();
                images.validateReference("data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes));
                prepared.add(new PreparedPhoto(bytes, type));
            } catch (java.io.IOException exception) { throw error(HttpStatus.BAD_REQUEST, "无法读取登记照片"); }
        }
        return prepared;
    }
    private void savePhotos(AssetRegistration registration, List<PreparedPhoto> prepared) {
        for (var photo : prepared) {
            var entity = new RegistrationPhoto(); entity.registrationId = registration.id; entity.revision = registration.photoRevision;
            entity.mediaType = photo.mediaType(); entity.data = photo.bytes(); photos.saveAndFlush(entity);
        }
    }
    private void requireEvidence(AssetRegistration registration, int photoCount) {
        if (clean(registration.serialNumber).isBlank() && photoCount == 0) throw error(HttpStatus.BAD_REQUEST, "SN不确定可以先留空，但需上传设备或铭牌照片供管理员核对");
    }
    private AssetRegistration visible(Long id, UserAccount actor) {
        var registration = registrations.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "登记不存在或无权访问"));
        if (!registration.ownerId.equals(actor.getId()) && !access.canReview(actor)) throw error(HttpStatus.NOT_FOUND, "登记不存在或无权访问");
        return registration;
    }
    private AssetRegistration locked(Long id) { return registrations.findForReview(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "登记不存在或无权访问")); }
    private void owned(AssetRegistration registration, UserAccount actor) {
        if (!registration.ownerId.equals(actor.getId())) throw error(HttpStatus.NOT_FOUND, "登记不存在或无权访问");
    }
    private void version(AssetRegistration registration, Long version) {
        if (version == null || version != registration.version) throw error(HttpStatus.CONFLICT, "登记已被其他操作更新，请刷新后重试");
    }
    private void open(AssetRegistration registration) {
        if (!OPEN.contains(registration.state)) throw error(HttpStatus.CONFLICT, "已结束的登记不能重新修改");
    }
    private void submitted(AssetRegistration registration) {
        if (!"SUBMITTED".equals(registration.state)) throw error(HttpStatus.CONFLICT, "只有待审核登记可以入库");
    }
    private void event(AssetRegistration registration, UserAccount actor, String text) {
        var event = new AssetRegistration.Event(); event.occurredAt = LocalDateTime.now(); event.actorName = actor.getDisplayName(); event.description = text;
        registration.events.add(event); registration.updatedAt = event.occurredAt;
    }
    private Summary summary(AssetRegistration r) {
        return new Summary(r.id, r.version, r.ownerId, r.ownerName, r.name, r.model, r.serialNumber, r.state, r.updatedAt, r.approvedAssetId, r.approvedAssetTag);
    }
    private Detail detail(AssetRegistration r) {
        boolean reviewer = access.canReview(access.current());
        return new Detail(summary(r), r.requestKey, r.department, r.location, r.cpu, r.memory, r.storage, r.notes, r.reviewNote, r.reviewerName,
                photos.findInfoByRegistrationIdAndRevisionOrderById(r.id, r.photoRevision).stream().map(p -> "/api/registrations/photos/" + p.getId()).toList(),
                r.events.stream().map(e -> new Event(e.occurredAt, e.actorName, e.description)).toList(),
                reviewer && !clean(r.serialNumber).isBlank() && assets.existsByManufacturerSerialNumberIgnoreCase(r.serialNumber));
    }
    private static String serialKey(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String blankToNull(String value) { return clean(value).isBlank() ? null : clean(value); }
    private static ApiException error(HttpStatus status, String message) { return new ApiException(status, message); }
}
