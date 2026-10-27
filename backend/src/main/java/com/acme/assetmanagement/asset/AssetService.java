package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.lookup.LookupRepository;
import com.acme.assetmanagement.lookup.AssetProfile;
import com.acme.assetmanagement.lookup.LookupType;
import com.acme.assetmanagement.lookup.LookupValue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class AssetService {
    private final AssetRepository assetRepository;
    private final LookupRepository lookupRepository;
    private final AuditLogService auditLogService;
    private final AssetTagGenerator assetTagGenerator;
    private final AssetImageStorageService assetImageStorageService;
    private final AssetAccess access;
    private final com.acme.assetmanagement.user.UserRepository users;

    public AssetService(AssetRepository assetRepository, LookupRepository lookupRepository,
                        AuditLogService auditLogService, AssetTagGenerator assetTagGenerator,
                        AssetImageStorageService assetImageStorageService, AssetAccess access,
                        com.acme.assetmanagement.user.UserRepository users) {
        this.assetRepository = assetRepository;
        this.lookupRepository = lookupRepository;
        this.auditLogService = auditLogService;
        this.assetTagGenerator = assetTagGenerator;
        this.assetImageStorageService = assetImageStorageService;
        this.access = access;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<AssetResponse> list(String search) {
        var scope = access.scope();
        return assetRepository.searchScoped(search == null ? "" : search.trim(),
                scope.type() == com.acme.assetmanagement.user.DataScope.ALL,
                scope.type() == com.acme.assetmanagement.user.DataScope.SELF, scope.userId(),
                scope.type() == com.acme.assetmanagement.user.DataScope.DEPARTMENT,
                scope.departments().isEmpty() ? java.util.Set.of("") : scope.departments())
                .stream().filter(scope::allows).map(asset -> AssetResponse.from(asset, scope::allows)).toList();
    }

    @Transactional(readOnly = true)
    public AssetResponse get(Long id) {
        return response(findAsset(id));
    }

    @Transactional(readOnly = true)
    public PublicAssetResponse getPublicByQrToken(String qrToken) {
        Asset asset = assetRepository.findByQrToken(qrToken)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "二维码对应的资产不存在或已被删除"));
        var scope = access.scope();
        access.require(scope, asset);
        return PublicAssetResponse.from(asset, scope::allows);
    }

    public AssetResponse create(AssetRequest request) {
        return create(request, null);
    }

    public AssetResponse create(AssetRequest request, Long cloneSourceId) {
        if (cloneSourceId != null) findAsset(cloneSourceId);
        String assetTag = clean(request.assetTag());
        if (assetTag == null) assetTag = assetTagGenerator.nextTag();
        validateUnique(request, null, assetTag);
        Asset asset = new Asset();
        apply(asset, request);
        // A cloned record is a different device, not a verified copy of domain membership.
        if (cloneSourceId != null && profileOf(asset.getCategory()) == AssetProfile.COMPUTER) {
            asset.setDomainJoinStatus(DomainJoinStatus.UNKNOWN);
        }
        rememberSuggestions(asset);
        asset.setAssetTag(assetTag);
        Asset saved = assetRepository.save(asset);
        applyBindings(saved, request.boundDisplayIds(), request.boundComputerId());
        saved = assetRepository.saveAndFlush(saved);
        AuditAction action = cloneSourceId == null ? AuditAction.ASSET_CREATE : AuditAction.ASSET_CLONE;
        String summary = cloneSourceId == null ? "新增资产“" + saved.getName() + "”"
                : "从资产 ID " + cloneSourceId + " 克隆生成新资产“" + saved.getName() + "”";
        auditLogService.success(action, "ASSET", saved.getId(), assetLabel(saved), summary, snapshot(saved));
        return response(saved);
    }

    public AssetResponse update(Long id, AssetRequest request, String version) {
        String assetTag = clean(request.assetTag());
        if (assetTag == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "更新资产时资产编号不能为空");
        }
        Asset asset = findAsset(id);
        if (version == null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "请刷新资产后重新编辑");
        if (!Long.toString(asset.getVersion()).equals(version.replace("\"", ""))) {
            throw new ApiException(HttpStatus.CONFLICT, "资产已被其他操作更新，请刷新后重新编辑");
        }
        String previousName = asset.getName();
        String previousDepartment = asset.getOwnershipDepartment();
        String previousGraphicsCard = asset.getGraphicsCard();
        Map<String, Object> before = snapshot(asset);
        if (!asset.getAssetTag().equals(assetTag)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "已创建资产的编号不能修改，以免与已打印标签不一致");
        }
        validateUnique(request, id, assetTag);
        apply(asset, request);
        rememberChangedSuggestions(previousName, previousDepartment, previousGraphicsCard, asset);
        applyBindings(asset, request.boundDisplayIds(), request.boundComputerId());
        Asset saved = assetRepository.saveAndFlush(asset);
        auditLogService.success(AuditAction.ASSET_UPDATE, "ASSET", saved.getId(), assetLabel(saved),
                "编辑资产“" + saved.getName() + "”", auditLogService.diff(before, snapshot(saved)));
        return response(saved);
    }

    public AssetResponse returnAsset(Long id, String version) {
        return returnAsset(findAsset(id), version);
    }

    public AssetResponse returnAssetByQrToken(String qrToken, String version) {
        Asset asset = assetRepository.findByQrToken(qrToken)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "二维码对应的资产不存在或已被删除"));
        access.require(asset);
        return returnAsset(asset, version);
    }

    private AssetResponse returnAsset(Asset asset, String version) {
        if (version == null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "请刷新资产后重新归还");
        if (!Long.toString(asset.getVersion()).equals(version.replace("\"", ""))) {
            throw new ApiException(HttpStatus.CONFLICT, "资产已被其他操作更新，请刷新后重新核对领用人");
        }
        Map<String, Object> before = snapshot(asset);
        if (!asset.isCheckedOut()) {
            throw new ApiException(HttpStatus.CONFLICT, "该资产当前未领出，无需归还");
        }
        asset.setCheckedOut(false);
        asset.setAssignedTo(null);
        asset.setAssignedUserId(null);
        if (!asset.isInMaintenanceOrScrapped()) lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "当前可用")
                .or(() -> lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用"))
                .ifPresent(asset::setStatus);
        Asset saved = assetRepository.saveAndFlush(asset);
        auditLogService.success(AuditAction.ASSET_RETURN, "ASSET", saved.getId(), assetLabel(saved),
                "归还资产并清空领用人", auditLogService.diff(before, snapshot(saved)));
        return response(saved);
    }

    public void delete(Long id) {
        Asset asset = findAsset(id);
        assetImageStorageService.removeAfterCommit(imageReferences(asset));
        Map<String, Object> before = snapshot(asset);
        String label = assetLabel(asset);
        String name = asset.getName();
        unlinkAll(asset);
        assetRepository.delete(asset);
        auditLogService.success(AuditAction.ASSET_DELETE, "ASSET", id, label,
                "永久删除资产“" + name + "”", before);
    }

    private Map<String, Object> snapshot(Asset asset) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("资产编号", asset.getAssetTag());
        values.put("资产名称", asset.getName());
        values.put("归属部门", asset.getOwnershipDepartment());
        values.put("所属公司", asset.getCompany() == null ? "" : asset.getCompany().getName());
        values.put("设备型号", asset.getModel() == null ? "" : asset.getModel().getName());
        values.put("资产分类", asset.getCategory() == null ? "" : asset.getCategory().getName());
        values.put("资产状态", asset.getStatus() == null ? "" : asset.getStatus().getName());
        values.put("存放位置", asset.getLocation() == null ? "" : asset.getLocation().getName());
        values.put("CPU", asset.getCpu());
        values.put("内存", asset.getMemory());
        values.put("硬盘", asset.getStorage());
        values.put("显卡", asset.getGraphicsCard());
        values.put("入域状态", asset.effectiveDomainJoinStatus() == null ? "—" : asset.effectiveDomainJoinStatus().label());
        values.put("厂家序列号", asset.getManufacturerSerialNumber());
        values.put("屏幕尺寸", asset.getScreenSize());
        values.put("分辨率", asset.getDisplayResolution());
        values.put("显示接口", asset.getDisplayInterface());
        values.put("订单号", asset.getOrderNumber());
        values.put("领用人", asset.getAssignedTo());
        values.put("领用账号ID", asset.getAssignedUserId());
        values.put("采购价格", asset.getPurchasePrice());
        values.put("当前价值", asset.getCurrentValue());
        values.put("随附配件", asset.getRelatedDevices().stream().map(AssetResponse.RelatedDeviceResponse::from).toList());
        values.put("配件", asset.getAccessories().stream().map(AssetResponse.AccessoryResponse::from).toList());
        values.put("自定义参数", asset.getCustomParameters().stream().map(AssetResponse.CustomParameterResponse::from).toList());
        values.put("绑定显示器", asset.getBoundDisplays().stream().map(Asset::getAssetTag).sorted().toList());
        values.put("绑定电脑", asset.getBoundComputers().stream().findFirst().map(Asset::getAssetTag).orElse(""));
        values.put("图片", imageReferences(asset).stream().distinct().toList());
        values.put("备注", asset.getNotes());
        return values;
    }

    private String assetLabel(Asset asset) {
        return asset.getAssetTag() + " · " + asset.getName();
    }

    private void validateUnique(AssetRequest request, Long id, String tag) {
        boolean tagExists = id == null
                ? assetRepository.existsByAssetTagIgnoreCase(tag)
                : assetRepository.existsByAssetTagIgnoreCaseAndIdNot(tag, id);
        if (tagExists) throw new ApiException(HttpStatus.CONFLICT, "资产编号“" + tag + "”已存在");

        String manufacturerSerialNumber = clean(request.manufacturerSerialNumber());
        if (manufacturerSerialNumber != null) {
            boolean serialExists = id == null
                    ? assetRepository.existsByManufacturerSerialNumberIgnoreCase(manufacturerSerialNumber)
                    : assetRepository.existsByManufacturerSerialNumberIgnoreCaseAndIdNot(manufacturerSerialNumber, id);
            if (serialExists) {
                throw new ApiException(HttpStatus.CONFLICT, "厂家序列号“" + manufacturerSerialNumber + "”已被其他资产使用");
            }
        }

        if (request.modelId() == null && clean(request.modelName()) == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "设备型号不能为空");
        }
        LookupValue status = findLookup(request.statusId(), LookupType.STATUS);
        if (isCheckedOutStatus(status) && clean(request.assignedTo()) == null && request.assignedUserId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "资产状态为“已经领出”时，必须填写领用人");
        }
    }

    private void apply(Asset asset, AssetRequest request) {
        Long ownerId = null;
        String ownerName = clean(request.assignedTo());
        boolean checkedOut = isCheckedOutStatus(findLookup(request.statusId(), LookupType.STATUS));
        // A metadata edit must not silently release a custodian kept by a repair/scrap workflow.
        if (asset.isCheckedOut() && asset.isInMaintenanceOrScrapped()
                && Objects.equals(asset.getStatus().getId(), request.statusId())) checkedOut = true;
        if (checkedOut && request.assignedUserId() != null) {
            var owner = users.findById(request.assignedUserId()).orElse(null);
            boolean unchangedOwner = Objects.equals(asset.getAssignedUserId(), request.assignedUserId());
            if (!unchangedOwner && (owner == null || !owner.isEnabled())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "领用账号不存在或已停用");
            }
            ownerId = request.assignedUserId();
            ownerName = owner == null ? asset.getAssignedTo() : owner.getDisplayName();
        }
        if (asset.isCheckedOut() && !checkedOut) {
            access.permission(com.acme.assetmanagement.user.Permission.ASSET_RETURN);
        } else if (asset.isCheckedOut() != checkedOut || !Objects.equals(asset.getAssignedUserId(), ownerId)
                || !Objects.equals(asset.getAssignedTo(), checkedOut ? ownerName : null)) {
            access.permission(com.acme.assetmanagement.user.Permission.ASSET_CHECKOUT);
        }
        // Check target scope before image/file writes and entity mutation.
        var scope = access.scope();
        String department = clean(request.ownershipDepartment());
        if (scope.type() == com.acme.assetmanagement.user.DataScope.SELF && !Objects.equals(scope.userId(), ownerId)
                || scope.type() == com.acme.assetmanagement.user.DataScope.DEPARTMENT
                && (department == null || !scope.departments().contains(department))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "不能保存到授权数据范围以外");
        }
        assetImageStorageService.removeAfterCommit(imageReferences(asset));
        requireEnabledSuggestion(LookupType.ASSET_NAME, request.name(), asset.getId() == null ? null : asset.getName());
        asset.setName(request.name().trim());
        asset.setOwnershipDepartment(clean(request.ownershipDepartment()));
        asset.setManufacturerSerialNumber(clean(request.manufacturerSerialNumber()));
        asset.setOrderNumber(clean(request.orderNumber()));
        asset.setCompany(findLookup(request.companyId(), LookupType.COMPANY));
        asset.setModel(resolveModel(request.modelId(), request.modelName(), asset.getModel()));
        LookupValue category = findLookup(request.categoryId(), LookupType.CATEGORY);
        asset.setCategory(category);
        AssetProfile profile = profileOf(category);
        if (profile != AssetProfile.COMPUTER && request.domainJoinStatus() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "只有电脑资产可以设置入域状态");
        }
        if (profile != AssetProfile.COMPUTER) asset.setDomainJoinStatus(null);
        else if (request.domainJoinStatus() != null) asset.setDomainJoinStatus(request.domainJoinStatus());
        // Omitted by an older client: preserve the stored value instead of clearing it.
        if (profile == AssetProfile.COMPUTER) {
            String cpu = clean(request.cpu());
            requireEnabledSuggestion(LookupType.CPU, cpu, asset.getId() == null ? null : asset.getCpu());
            asset.setCpu(cpu);
            rememberCpu(cpu);
            asset.setMemory(clean(request.memory()));
            asset.setStorage(clean(request.storage()));
            asset.setGraphicsCard(clean(request.graphicsCard()));
            asset.setScreenSize(null);
            asset.setDisplayResolution(null);
            asset.setDisplayInterface(null);
        } else if (profile == AssetProfile.DISPLAY) {
            asset.setCpu(null);
            asset.setMemory(null);
            asset.setStorage(null);
            asset.setGraphicsCard(null);
            asset.setScreenSize(clean(request.screenSize()));
            asset.setDisplayResolution(clean(request.displayResolution()));
            asset.setDisplayInterface(clean(request.displayInterface()));
        } else {
            asset.setCpu(null);
            asset.setMemory(null);
            asset.setStorage(null);
            asset.setGraphicsCard(null);
            asset.setScreenSize(null);
            asset.setDisplayResolution(null);
            asset.setDisplayInterface(null);
        }
        LookupValue status = findLookup(request.statusId(), LookupType.STATUS);
        asset.setStatus(status);
        asset.setLocation(findLookup(request.locationId(), LookupType.LOCATION));
        asset.setPurchasePrice(request.purchasePrice());
        asset.setCurrentValue(request.currentValue());
        asset.setCheckedOut(checkedOut);
        asset.setAssignedTo(checkedOut ? ownerName : null);
        asset.setAssignedUserId(checkedOut ? ownerId : null);
        // “开放申领”功能已经从产品中移除；保留数据库列仅用于兼容已有数据。
        asset.setRequestable(false);
        List<String> images = request.imageUrls() == null
                ? clean(request.imageUrl()) == null ? List.of() : List.of(request.imageUrl().trim())
                : request.imageUrls().stream().map(this::clean).filter(Objects::nonNull).distinct().toList();
        if (images.size() > 5) throw new ApiException(HttpStatus.BAD_REQUEST, "资产图片最多上传5张");
        images.stream().filter(image -> image.startsWith("/api/public/asset-images/"))
                .forEach(this::requireImageReference);
        images = assetImageStorageService.persistReferences(images);
        asset.setImageUrls(images);
        asset.setImageUrl(images.isEmpty() ? null : images.getFirst());
        asset.setNotes(clean(request.notes()));
        asset.setRelatedDevices(request.relatedDevices() == null ? List.of() : request.relatedDevices().stream()
                .map(device -> new RelatedDevice(device.name().trim(), clean(device.model()), clean(device.serialNumber()),
                        clean(device.orderNumber()), clean(device.specification()), device.quantity()))
                .toList());
        asset.setAccessories(request.accessories() == null ? List.of() : request.accessories().stream()
                .map(accessory -> new AssetAccessory(accessory.name().trim(), clean(accessory.specification()),
                        accessory.quantity()))
                .toList());
        asset.setCustomParameters(request.customParameters() == null ? List.of() : request.customParameters().stream()
                .map(parameter -> new AssetCustomParameter(parameter.name().trim(), parameter.value().trim()))
                .toList());
        if (profile == AssetProfile.GENERAL) {
            category.setParameterTemplate(asset.getCustomParameters().stream().map(AssetCustomParameter::getName).toList());
        }
    }

    void bindByAssetTags(Long assetId, List<String> displayTags, String computerTag) {
        List<Long> displayIds = displayTags == null ? List.of() : displayTags.stream()
                .map(String::trim).filter(tag -> !tag.isBlank())
                .map(tag -> assetRepository.findByAssetTagIgnoreCase(tag)
                        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "绑定显示器资产编号“" + tag + "”不存在")))
                .map(Asset::getId).toList();
        Long computerId = clean(computerTag) == null ? null : assetRepository.findByAssetTagIgnoreCase(computerTag.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "绑定电脑资产编号“" + computerTag.trim() + "”不存在"))
                .getId();
        Asset asset = findAsset(assetId);
        // Import rows only add declared relationships; an empty cell must not erase another row's binding.
        applyBindings(asset, displayIds, computerId, false);
        assetRepository.save(asset);
    }

    private void applyBindings(Asset asset, List<Long> displayIds, Long computerId) {
        applyBindings(asset, displayIds, computerId, true);
    }

    private void applyBindings(Asset asset, List<Long> displayIds, Long computerId, boolean replaceExisting) {
        AssetProfile profile = profileOf(asset.getCategory());
        List<Long> uniqueDisplayIds = displayIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(displayIds));
        if (profile == AssetProfile.DISPLAY && !uniqueDisplayIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "显示器不能再绑定其他显示器");
        }
        if (profile == AssetProfile.COMPUTER && computerId != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "电脑资产不能绑定到另一台电脑");
        }
        if (profile == AssetProfile.GENERAL && (!uniqueDisplayIds.isEmpty() || computerId != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "通用设备不支持电脑与显示器绑定");
        }

        if (replaceExisting) unlinkAll(asset);
        if (profile == AssetProfile.COMPUTER) {
            for (Long displayId : uniqueDisplayIds) {
                if (asset.getId() != null && asset.getId().equals(displayId)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "资产不能绑定自身");
                }
                Asset display = findAsset(displayId);
                access.permission(com.acme.assetmanagement.user.Permission.ASSET_EDIT);
                if (profileOf(display.getCategory()) != AssetProfile.DISPLAY) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "只能绑定分类模板为“显示设备”的资产");
                }
                if (!display.getBoundComputers().isEmpty() && !display.getBoundComputers().contains(asset)) {
                    Asset existing = display.getBoundComputers().iterator().next();
                    throw new ApiException(HttpStatus.CONFLICT, "显示器“" + display.getAssetTag() + "”已绑定电脑“" + existing.getAssetTag() + "”");
                }
                asset.getBoundDisplays().add(display);
                display.getBoundComputers().add(asset);
            }
        } else if (profile == AssetProfile.DISPLAY && computerId != null) {
            if (asset.getId() != null && asset.getId().equals(computerId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "资产不能绑定自身");
            }
            Asset computer = findAsset(computerId);
            access.permission(com.acme.assetmanagement.user.Permission.ASSET_EDIT);
            if (profileOf(computer.getCategory()) != AssetProfile.COMPUTER) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "显示器只能绑定分类模板为“电脑设备”的资产");
            }
            if (!asset.getBoundComputers().isEmpty() && !asset.getBoundComputers().contains(computer)) {
                throw new ApiException(HttpStatus.CONFLICT, "显示器“" + asset.getAssetTag() + "”不能同时绑定多台电脑");
            }
            computer.getBoundDisplays().add(asset);
            asset.getBoundComputers().add(computer);
        }
    }

    private void unlinkAll(Asset asset) {
        for (Asset linked : java.util.stream.Stream.concat(asset.getBoundComputers().stream(), asset.getBoundDisplays().stream()).toList()) {
            access.require(linked);
            access.permission(com.acme.assetmanagement.user.Permission.ASSET_EDIT);
        }
        for (Asset computer : new ArrayList<>(asset.getBoundComputers())) {
            computer.getBoundDisplays().remove(asset);
            asset.getBoundComputers().remove(computer);
        }
        for (Asset display : new ArrayList<>(asset.getBoundDisplays())) {
            display.getBoundComputers().remove(asset);
            asset.getBoundDisplays().remove(display);
        }
    }

    private AssetProfile profileOf(LookupValue category) {
        return category.getAssetProfile() == null ? AssetProfile.infer(category.getName()) : category.getAssetProfile();
    }

    private Asset findAsset(Long id) {
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "资产不存在或已被删除"));
        access.require(asset);
        return asset;
    }

    private AssetResponse response(Asset asset) {
        var scope = access.scope();
        return AssetResponse.from(asset, scope::allows);
    }

    @Transactional(readOnly = true)
    public void requireImageReference(String reference) {
        var scope = access.scope();
        if (assetRepository.findImageOwners(reference).stream().noneMatch(scope::allows)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "图片不存在或无权访问");
        }
    }

    public void requireImportScope() {
        if (access.scope().type() != com.acme.assetmanagement.user.DataScope.ALL) {
            throw new ApiException(HttpStatus.FORBIDDEN, "批量导入需要全部资产数据范围");
        }
    }

    private LookupValue findLookup(Long id, LookupType expectedType) {
        LookupValue value = lookupRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, expectedType.getLabel() + "选项不存在"));
        if (value.getType() != expectedType) {
            throw new ApiException(HttpStatus.BAD_REQUEST, expectedType.getLabel() + "选项类型不正确");
        }
        return value;
    }

    private LookupValue resolveModel(Long id, String modelName, LookupValue currentModel) {
        LookupValue model;
        if (id != null) model = findLookup(id, LookupType.MODEL);
        else {
            String name = clean(modelName);
            if (name == null) throw new ApiException(HttpStatus.BAD_REQUEST, "设备型号不能为空");
            model = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.MODEL, name)
                    .orElseGet(() -> lookupRepository.save(new LookupValue(LookupType.MODEL, name)));
        }
        if (model.isDisabled() && (currentModel == null || !Objects.equals(currentModel.getId(), model.getId())))
            throw new ApiException(HttpStatus.CONFLICT, "型号“" + model.getName() + "”已停用，请选择其他型号或联系管理员启用");
        return model;
    }

    private void rememberCpu(String cpu) {
        if (cpu == null) return;
        lookupRepository.findByTypeAndNameIgnoreCase(LookupType.CPU, cpu)
                .orElseGet(() -> lookupRepository.save(new LookupValue(LookupType.CPU, cpu)));
    }

    private void requireEnabledSuggestion(LookupType type, String value, String current) {
        String name = clean(value), previous = clean(current);
        if (name == null || previous != null && name.equalsIgnoreCase(previous)) return;
        lookupRepository.findByTypeAndNameIgnoreCase(type, name).filter(LookupValue::isDisabled).ifPresent(option -> {
            throw new ApiException(HttpStatus.CONFLICT, type.getLabel() + "“" + name + "”已停用，请选择其他选项或联系管理员启用");
        });
    }

    private void rememberSuggestions(Asset asset) {
        rememberSuggestion(LookupType.ASSET_NAME, asset.getName());
        rememberSuggestion(LookupType.DEPARTMENT, asset.getOwnershipDepartment());
        rememberSuggestion(LookupType.GRAPHICS_CARD, asset.getGraphicsCard());
    }

    private void rememberChangedSuggestions(String previousName, String previousDepartment,
                                              String previousGraphicsCard, Asset asset) {
        if (!sameText(previousName, asset.getName())) rememberSuggestion(LookupType.ASSET_NAME, asset.getName());
        if (!sameText(previousDepartment, asset.getOwnershipDepartment())) rememberSuggestion(LookupType.DEPARTMENT, asset.getOwnershipDepartment());
        if (!sameText(previousGraphicsCard, asset.getGraphicsCard())) rememberSuggestion(LookupType.GRAPHICS_CARD, asset.getGraphicsCard());
    }

    private void rememberSuggestion(LookupType type, String value) {
        String name = clean(value);
        if (name == null) return;
        lookupRepository.findByTypeAndNameIgnoreCase(type, name)
                .orElseGet(() -> lookupRepository.save(new LookupValue(type, name)));
    }

    private boolean sameText(String left, String right) {
        return Objects.equals(clean(left), clean(right));
    }

    private boolean isCheckedOutStatus(LookupValue status) {
        String name = status.getName();
        return name.contains("在用") || name.contains("领出");
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private List<String> imageReferences(Asset asset) {
        List<String> references = new ArrayList<>(asset.getImageUrls());
        if (asset.getImageUrl() != null) references.add(asset.getImageUrl());
        return references;
    }
}
