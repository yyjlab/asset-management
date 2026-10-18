package com.acme.assetmanagement.config;

import com.acme.assetmanagement.asset.Asset;
import com.acme.assetmanagement.asset.AssetRepository;
import com.acme.assetmanagement.lookup.LookupRepository;
import com.acme.assetmanagement.lookup.AssetProfile;
import com.acme.assetmanagement.lookup.LookupType;
import com.acme.assetmanagement.lookup.LookupValue;
import com.acme.assetmanagement.setting.SystemSetting;
import com.acme.assetmanagement.setting.SystemSettingRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class DataInitializer implements CommandLineRunner {
    private static final String MANAGED_SUGGESTIONS_INITIALIZED = "MANAGED_SUGGESTIONS_V1";

    private final LookupRepository lookupRepository;
    private final AssetRepository assetRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final com.acme.assetmanagement.asset.AssetTagSequenceRepository sequenceRepository;

    public DataInitializer(LookupRepository lookupRepository, AssetRepository assetRepository,
                           SystemSettingRepository systemSettingRepository,
                           com.acme.assetmanagement.asset.AssetTagSequenceRepository sequenceRepository) {
        this.lookupRepository = lookupRepository;
        this.assetRepository = assetRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.sequenceRepository = sequenceRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        var lockDate = com.acme.assetmanagement.asset.AssetTagGenerator.ALLOCATION_LOCK_DATE;
        if (!sequenceRepository.existsById(lockDate)) {
            sequenceRepository.saveAndFlush(new com.acme.assetmanagement.asset.AssetTagSequence(lockDate, 0));
        }
        if (lookupRepository.count() > 0) {
            migrateLegacyAssetNumbers();
            ensureFixedStatus("已报废");
            normalizeCategoryProfiles();
            normalizeLegacyUsageState();
            initializeManagedSuggestions();
            return;
        }

        LookupValue company = save(LookupType.COMPANY, "示例科技有限公司");
        LookupValue model = save(LookupType.MODEL, "ThinkPad T14 Gen 5");
        LookupValue category = save(LookupType.CATEGORY, "笔记本电脑", AssetProfile.COMPUTER);
        LookupValue status = save(LookupType.STATUS, "可领用");
        LookupValue location = save(LookupType.LOCATION, "总部 · 3楼设备间");
        save(LookupType.STATUS, "在用");
        save(LookupType.STATUS, "维修中");
        save(LookupType.STATUS, "已报废");
        save(LookupType.CPU, "Intel Core Ultra 7 155U");
        save(LookupType.CATEGORY, "台式机", AssetProfile.COMPUTER);
        save(LookupType.CATEGORY, "显示器", AssetProfile.DISPLAY);

        Asset asset = new Asset();
        asset.setAssetTag("202609010001");
        asset.setName("研发部办公笔记本");
        asset.setCpu("Intel Core Ultra 7 155U");
        asset.setMemory("32GB DDR5");
        asset.setStorage("1TB NVMe SSD");
        asset.setGraphicsCard("Intel Graphics 集成显卡");
        asset.setCompany(company);
        asset.setModel(model);
        asset.setCategory(category);
        asset.setStatus(status);
        asset.setLocation(location);
        asset.setPurchasePrice(new BigDecimal("6999.00"));
        asset.setCurrentValue(new BigDecimal("6200.00"));
        asset.setCheckedOut(false);
        asset.setRequestable(false);
        asset.setNotes("这是系统首次启动时生成的演示资产，可直接编辑或删除。");
        assetRepository.save(asset);
        initializeManagedSuggestions();
    }

    private void migrateLegacyAssetNumbers() {
        for (Asset asset : assetRepository.findAll()) {
            boolean changed = false;
            if (asset.getAssetTag() == null || !asset.getAssetTag().matches("\\d{12}")) {
                String candidate = "2026" + String.format("%08d", asset.getId());
                while (assetRepository.existsByAssetTagIgnoreCase(candidate)) {
                    long next = Long.parseLong(candidate.substring(4)) + 1;
                    candidate = "2026" + String.format("%08d", next);
                }
                asset.setAssetTag(candidate);
                changed = true;
            }
            if (asset.getQrToken() == null || asset.getQrToken().isBlank()) {
                asset.setQrToken(UUID.randomUUID().toString());
                changed = true;
            }
            if (changed) assetRepository.save(asset);
        }
    }

    private void ensureFixedStatus(String name) {
        lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, name)
                .orElseGet(() -> lookupRepository.save(new LookupValue(LookupType.STATUS, name)));
    }

    private void normalizeLegacyUsageState() {
        LookupValue available = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "当前可用")
                .or(() -> lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用"))
                .orElse(null);
        LookupValue checkedOut = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "已经领出")
                .or(() -> lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用"))
                .orElse(null);
        if (available == null || checkedOut == null) return;

        for (Asset asset : assetRepository.findAll()) {
            // These lifecycle states may still retain a custodian until the physical return.
            if (asset.isInMaintenanceOrScrapped()) continue;
            String statusName = asset.getStatus().getName();
            boolean statusMeansCheckedOut = statusName.contains("在用") || statusName.contains("领出");
            if (asset.isCheckedOut()) {
                if (asset.getStatus().getId().equals(checkedOut.getId())) continue;
                asset.setStatus(checkedOut);
                assetRepository.save(asset);
            } else if (statusMeansCheckedOut) {
                asset.setStatus(available);
                asset.setAssignedTo(null);
                assetRepository.save(asset);
            }
        }
    }

    private void normalizeCategoryProfiles() {
        for (LookupValue value : lookupRepository.findByTypeOrderByNameAsc(LookupType.CATEGORY)) {
            if (value.getAssetProfile() == null) {
                value.setAssetProfile(AssetProfile.infer(value.getName()));
                lookupRepository.save(value);
            }
        }
    }

    private void initializeManagedSuggestions() {
        if (systemSettingRepository.existsById(MANAGED_SUGGESTIONS_INITIALIZED)) return;
        for (Asset asset : assetRepository.findAll()) {
            rememberSuggestion(LookupType.ASSET_NAME, asset.getName());
            rememberSuggestion(LookupType.DEPARTMENT, asset.getOwnershipDepartment());
            rememberSuggestion(LookupType.GRAPHICS_CARD, asset.getGraphicsCard());
        }
        systemSettingRepository.save(new SystemSetting(MANAGED_SUGGESTIONS_INITIALIZED, "true"));
    }

    private void rememberSuggestion(LookupType type, String name) {
        if (name == null || name.isBlank()) return;
        String cleanName = name.trim();
        lookupRepository.findByTypeAndNameIgnoreCase(type, cleanName)
                .orElseGet(() -> lookupRepository.save(new LookupValue(type, cleanName)));
    }

    private LookupValue save(LookupType type, String name) {
        return lookupRepository.save(new LookupValue(type, name));
    }

    private LookupValue save(LookupType type, String name, AssetProfile profile) {
        return lookupRepository.save(new LookupValue(type, name, profile));
    }
}
