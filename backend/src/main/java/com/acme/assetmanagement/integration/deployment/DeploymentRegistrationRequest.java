package com.acme.assetmanagement.integration.deployment;

import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

public record DeploymentRegistrationRequest(String externalRegistrationId, String assetTag, Long categoryId,
        String name, Instant capturedAt, Operator operator, Snapshot snapshot) {
    public record Operator(String id, String displayName) {}
    public record Disk(String model, long capacityBytes, String busType) {}
    public record Snapshot(String model, String cpu, String memory, List<Disk> internalDisks,
                           List<String> graphicsCards, String manufacturerSerialNumber) {}

    public static DeploymentRegistrationRequest parse(byte[] body, ObjectMapper json) {
        final Map<?, ?> root;
        try { root = object(json.readerFor(Object.class).with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .readValue(body), "request"); }
        catch (DeploymentApiException exception) { throw exception; }
        catch (RuntimeException exception) {
            throw new DeploymentApiException(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "请求必须是有效的 JSON 对象");
        }
        keys(root, "request", "externalRegistrationId", "assetTag", "categoryId", "name", "capturedAt", "operator", "snapshot");
        String externalId = text(root, "externalRegistrationId", 128, true);
        if (!externalId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) invalid("externalRegistrationId", "只允许字母、数字、点、横线、下划线和冒号");
        Object rawTag = root.get("assetTag");
        if (!(rawTag instanceof String tag) || !tag.matches("[0-9]{12}")) invalid("assetTag", "必须是保留前导零的12位数字字符串");
        long category = positive(root.get("categoryId"), "categoryId");
        String capture = text(root, "capturedAt", 40, true);
        Instant captured;
        try { captured = Instant.parse(capture); }
        catch (RuntimeException exception) { throw validation("capturedAt", "必须是带时区的 ISO-8601 时间"); }
        if (captured.isAfter(Instant.now().plusSeconds(86400))) invalid("capturedAt", "采集时间不能超过未来24小时");
        var operator = object(root.get("operator"), "operator");
        keys(operator, "operator", "id", "displayName");
        var snapshot = object(root.get("snapshot"), "snapshot");
        keys(snapshot, "snapshot", "model", "cpu", "memory", "internalDisks", "graphicsCards", "manufacturerSerialNumber");
        var disks = new ArrayList<Disk>();
        for (Object raw : array(snapshot.get("internalDisks"), "snapshot.internalDisks", 32)) {
            var disk = object(raw, "snapshot.internalDisks[]");
            keys(disk, "snapshot.internalDisks[]", "model", "capacityBytes", "busType");
            String bus = text(disk, "busType", 16, true);
            if (!Set.of("NVMe", "SATA", "ATA", "SAS", "SCSI", "RAID", "MMC").contains(bus))
                invalid("snapshot.internalDisks[].busType", "只接受明确的内置物理硬盘总线，不接受 USB、Unknown 或分区");
            disks.add(new Disk(text(disk, "model", 120, false), positive(disk.get("capacityBytes"), "snapshot.internalDisks[].capacityBytes"), bus));
        }
        var graphics = new ArrayList<String>();
        for (Object raw : array(snapshot.get("graphicsCards"), "snapshot.graphicsCards", 8)) {
            if (!(raw instanceof String) || ((String)raw).isBlank() || ((String)raw).length() > 200)
                invalid("snapshot.graphicsCards[]", "须为1到200字符的显卡名称");
            graphics.add(((String)raw).trim());
        }
        return new DeploymentRegistrationRequest(externalId, (String)rawTag, category, text(root, "name", 160, false), captured,
                new Operator(text(operator, "id", 120, true), text(operator, "displayName", 120, true)),
                new Snapshot(text(snapshot, "model", 120, true), text(snapshot, "cpu", 200, false),
                        text(snapshot, "memory", 200, false), List.copyOf(disks), List.copyOf(graphics),
                        text(snapshot, "manufacturerSerialNumber", 160, false)));
    }
    private static Map<?, ?> object(Object value, String field) {
        if (value instanceof Map<?, ?> map) return map;
        throw validation(field, "须为对象");
    }
    private static List<?> array(Object value, String field, int max) {
        if (value instanceof List<?> list && list.size() <= max) return list;
        throw validation(field, "须为数组，最多" + max + "项；未采集到时传空数组");
    }
    private static String text(Map<?, ?> object, String field, int max, boolean required) {
        Object raw = object.get(field);
        if (raw == null && !required) return null;
        if (!(raw instanceof String text)) throw validation(field, "须为字符串");
        if (text.length() > max || required && text.isBlank() || text.chars().anyMatch(c -> Character.isISOControl(c)))
            throw validation(field, "长度须为" + (required ? "1" : "0") + "到" + max + "字符，不能含控制字符");
        return text.isBlank() ? null : text.trim();
    }
    private static long positive(Object value, String field) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue() <= 0)
            throw validation(field, "须为正整数");
        return ((Number)value).longValue();
    }
    private static void keys(Map<?, ?> value, String field, String... allowed) {
        var known = Set.of(allowed);
        if (value.keySet().stream().anyMatch(key -> !known.contains(key))) invalid(field, "包含不支持的字段");
    }
    private static void invalid(String field, String message) { throw validation(field, message); }
    private static DeploymentApiException validation(String field, String message) {
        return new DeploymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", field + ": " + message, List.of(field));
    }
}
