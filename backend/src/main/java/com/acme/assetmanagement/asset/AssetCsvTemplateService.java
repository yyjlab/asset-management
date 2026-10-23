package com.acme.assetmanagement.asset;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssetCsvTemplateService {
    private static final String UTF8_BOM = "\uFEFF";
    private final ObjectMapper objectMapper;

    private static final String[] HEADERS = {
            "资产编号*",
            "资产名称*",
            "所属公司*",
            "归属部门",
            "设备型号*",
            "资产分类*",
            "资产状态*",
            "存放位置*",
            "CPU",
            "内存",
            "硬盘",
            "显卡",
            "厂家序列号",
            "屏幕尺寸",
            "分辨率",
            "显示接口",
            "订单号",
            "采购价格(元)",
            "当前价值(元)",
            "领用人",
            "图片地址",
            "备注",
            "绑定显示器资产编号(分号分隔)",
            "绑定电脑资产编号",
            "随附配件(JSON)",
            "自定义参数(JSON)",
            "图片地址(JSON)",
            "入域状态"
    };

    public AssetCsvTemplateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] createAssetExport(List<AssetResponse> assets) {
        StringBuilder csv = new StringBuilder(UTF8_BOM);
        csv.append(String.join(",", HEADERS)).append("\r\n");
        assets.forEach(asset -> csv.append(row(asset)).append("\r\n"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String row(AssetResponse asset) {
        return String.join(",", List.of(
                csv(asset.assetTag()), csv(asset.name()), csv(asset.company().name()), csv(asset.ownershipDepartment()),
                csv(asset.model().name()), csv(asset.category().name()), csv(asset.status().name()), csv(asset.location().name()),
                csv(asset.cpu()), csv(asset.memory()), csv(asset.storage()), csv(asset.graphicsCard()),
                csv(asset.manufacturerSerialNumber()), csv(asset.screenSize()), csv(asset.displayResolution()),
                csv(asset.displayInterface()), csv(asset.orderNumber()), csv(asset.purchasePrice()), csv(asset.currentValue()),
                csv(asset.assignedTo()), csv(asset.imageUrl()), csv(asset.notes()),
                csv(asset.boundDisplays().stream().map(AssetResponse.AssetLinkResponse::assetTag).toList(), ";"),
                csv(asset.boundComputer() == null ? null : asset.boundComputer().assetTag()),
                csv(devicesJson(asset)),
                csv(customParametersJson(asset)),
                csv(asset.imageUrls().isEmpty() ? null : objectMapper.writeValueAsString(asset.imageUrls())),
                csv(asset.domainJoinStatus() == null ? null : asset.domainJoinStatus().label())
        ));
    }

    private String devicesJson(AssetResponse asset) {
        List<Map<String, Object>> devices = new ArrayList<>();
        asset.relatedDevices().forEach(device -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", device.name());
            value.put("model", device.model());
            value.put("serialNumber", device.serialNumber());
            value.put("orderNumber", device.orderNumber());
            value.put("specification", device.specification());
            value.put("quantity", device.quantity());
            devices.add(value);
        });
        asset.accessories().forEach(accessory -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", accessory.name());
            value.put("specification", accessory.specification());
            value.put("quantity", accessory.quantity());
            devices.add(value);
        });
        if (devices.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(devices);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成资产配件数据", exception);
        }
    }

    private String customParametersJson(AssetResponse asset) {
        if (asset.customParameters().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(asset.customParameters());
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成资产自定义参数", exception);
        }
    }

    private static String csv(List<String> values, String delimiter) {
        return csv(String.join(delimiter, values));
    }

    private static String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.isEmpty() && ("=+-@\t\r\n".indexOf(text.charAt(0)) >= 0
                || (!text.stripLeading().isEmpty() && "=+-@".indexOf(text.stripLeading().charAt(0)) >= 0))) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
