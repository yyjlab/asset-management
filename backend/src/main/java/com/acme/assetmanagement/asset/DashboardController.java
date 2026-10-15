package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.lookup.AssetProfile;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;

/** Authenticated, company-wide presentation data only. Never return asset IDs, owners, tokens or prices. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final AssetRepository assets;
    public DashboardController(AssetRepository assets) { this.assets = assets; }

    @GetMapping
    @Transactional(readOnly = true)
    public Overview overview() {
        List<Asset> all = assets.search("");
        List<Asset> computers = all.stream().filter(a -> profile(a) == AssetProfile.COMPUTER).toList();
        List<Asset> displays = all.stream().filter(a -> profile(a) == AssetProfile.DISPLAY).toList();
        int available = 0, checkedOut = 0, maintenance = 0, scrapped = 0, complete = 0, fields = 0, images = 0, departments = 0;
        for (Asset a : computers) {
            boolean broken = a.getStatus().getName().matches(".*(维修|维护).*"), retired = a.getStatus().getName().contains("报废");
            if (!a.isCheckedOut() && !broken && !retired) available++;
            if (a.isCheckedOut() && !retired) checkedOut++;
            if (broken) maintenance++;
            if (retired) scrapped++;
            int filled = (filled(a.getCpu()) ? 1 : 0) + (filled(a.getMemory()) ? 1 : 0) + (filled(a.getStorage()) ? 1 : 0);
            fields += filled;
            if (filled == 3) complete++;
            if (!filled(a.getImageUrl()) && a.getImageUrls().isEmpty()) images++;
            if (!filled(a.getOwnershipDepartment())) departments++;
        }
        int unbound = (int) displays.stream().filter(a -> a.getBoundComputers().isEmpty()).count();
        return new Overview(all.size(), computers.size(), displays.size(), available, checkedOut, maintenance, scrapped,
                computers.isEmpty() ? 0 : (int) Math.round(fields * 100.0 / (computers.size() * 3)), complete, images, departments,
                unbound, rank(computers, Asset::getCpu, 5), rank(computers, Asset::getMemory, 5), rank(computers, Asset::getStorage, 5),
                rank(computers, Asset::getOwnershipDepartment, 4), rank(computers, a -> a.getLocation().getName(), 4));
    }
    private static AssetProfile profile(Asset a) {
        return a.getCategory().getAssetProfile() == null ? AssetProfile.infer(a.getCategory().getName()) : a.getCategory().getAssetProfile();
    }
    private static boolean filled(String value) { return value != null && !value.isBlank(); }
    private static List<RankedItem> rank(List<Asset> assets, Function<Asset, String> field, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        assets.forEach(a -> counts.merge(filled(field.apply(a)) ? field.apply(a).trim() : "未填写", 1, Integer::sum));
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit).map(e -> new RankedItem(e.getKey(), e.getValue(), assets.isEmpty() ? 0 : (int) Math.round(e.getValue() * 100.0 / assets.size()))).toList();
    }
    public record RankedItem(String label, int count, int percent) {}
    public record Overview(int total, int computerCount, int displayCount, int available, int checkedOut, int maintenance,
                           int scrapped, int configurationRate, int completeComputers, int missingImages, int missingDepartments,
                           int unboundDisplays, List<RankedItem> cpu, List<RankedItem> memory, List<RankedItem> storage,
                           List<RankedItem> departments, List<RankedItem> locations) {}
}
