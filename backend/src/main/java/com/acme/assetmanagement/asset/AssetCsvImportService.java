package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.lookup.LookupRepository;
import com.acme.assetmanagement.lookup.LookupType;
import com.acme.assetmanagement.lookup.LookupValue;
import com.acme.assetmanagement.lookup.AssetProfile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AssetCsvImportService {
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final List<String> REQUIRED_HEADERS = List.of(
            "资产编号*", "资产名称*", "所属公司*", "资产分类*", "资产状态*", "存放位置*"
    );

    private final AssetRepository assetRepository;
    private final LookupRepository lookupRepository;
    private final AssetService assetService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final AssetImageStorageService imageStorage;

    public AssetCsvImportService(AssetRepository assetRepository, LookupRepository lookupRepository,
                                 AssetService assetService, AuditLogService auditLogService,
                                 ObjectMapper objectMapper, Validator validator, AssetImageStorageService imageStorage) {
        this.assetRepository = assetRepository;
        this.lookupRepository = lookupRepository;
        this.assetService = assetService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.imageStorage = imageStorage;
    }

    @Transactional(readOnly = true)
    public PreviewResponse preview(MultipartFile file) {
        ParsedFile parsed = parse(file);
        ValidationResult validation = validateRows(parsed.rows());
        return new PreviewResponse(parsed.rows().size(), parsed.rows().size() - validation.invalidRows().size(),
                validation.errors(), validation.warnings(), parsed.rows().stream().limit(20)
                .map(row -> new PreviewRow(row.rowNumber(), row.value("资产编号*"), row.value("资产名称*"),
                        row.value("资产状态*"), !validation.invalidRows().contains(row.rowNumber()))).toList(),
                validation.errors().isEmpty());
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ImportResponse commit(MultipartFile file) {
        ParsedFile parsed = parse(file);
        ValidationResult validation = validateRows(parsed.rows());
        if (!validation.errors().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "CSV 中有 " + validation.errors().size() + " 处错误，请重新预览并修正后再导入");
        }

        Set<String> createdLookups = new LinkedHashSet<>();
        List<PendingBinding> pendingBindings = new ArrayList<>();
        int imported = 0;
        for (ImportRow row : parsed.rows()) {
            LookupValue company = resolveLookup(LookupType.COMPANY, row.value("所属公司*"), true, createdLookups);
            LookupValue model = resolveLookup(LookupType.MODEL, modelValue(row), true, createdLookups);
            LookupValue category = resolveLookup(LookupType.CATEGORY, row.value("资产分类*"), true, createdLookups);
            LookupValue status = resolveLookup(LookupType.STATUS, row.value("资产状态*"), false, createdLookups);
            LookupValue location = resolveLookup(LookupType.LOCATION, row.value("存放位置*"), true, createdLookups);
            AssetRequest request = requestFor(row, company.getId(), model.getId(), category.getId(), status.getId(), location.getId());
            Set<ConstraintViolation<AssetRequest>> violations = validator.validate(request);
            if (!violations.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "第 " + row.rowNumber() + " 行：" + violations.iterator().next().getMessage());
            }
            AssetResponse createdAsset = assetService.create(request);
            pendingBindings.add(new PendingBinding(createdAsset.id(),
                    bindingTags(row.value("绑定显示器资产编号(分号分隔)")),
                    clean(row.value("绑定电脑资产编号"))));
            imported++;
        }
        for (PendingBinding binding : pendingBindings) {
            assetService.bindByAssetTags(binding.assetId(), binding.displayTags(), binding.computerTag());
        }
        auditLogService.success(AuditAction.CSV_IMPORT, "CSV", null, file.getOriginalFilename(),
                "通过 CSV 导入 " + imported + " 项资产", Map.of("导入数量", imported, "新建选项数量", createdLookups.size()));
        return new ImportResponse(imported, createdLookups.size(), List.copyOf(createdLookups));
    }

    private ValidationResult validateRows(List<ImportRow> rows) {
        List<RowMessage> errors = new ArrayList<>();
        List<RowMessage> warnings = new ArrayList<>();
        Set<Integer> invalidRows = new HashSet<>();
        Set<String> seenTags = new HashSet<>();
        Set<String> fileTags = rows.stream().map(row -> row.value("资产编号*")).filter(tag -> !tag.isBlank()).collect(java.util.stream.Collectors.toSet());
        Set<String> seenManufacturerSerialNumbers = new HashSet<>();
        for (ImportRow row : rows) {
            for (String header : REQUIRED_HEADERS) {
                if (row.value(header).isBlank()) add(errors, invalidRows, row, header, "必填字段不能为空");
            }
            if (modelValue(row).isBlank()) add(errors, invalidRows, row, "设备型号*", "必填字段不能为空");
            String tag = row.value("资产编号*");
            if (!tag.matches("\\d{12}")) add(errors, invalidRows, row, "资产编号*", "必须是12位数字");
            else if (!seenTags.add(tag)) add(errors, invalidRows, row, "资产编号*", "文件内资产编号重复");
            else if (assetRepository.existsByAssetTagIgnoreCase(tag)) add(errors, invalidRows, row, "资产编号*", "系统中已存在");

            String manufacturerSerialNumber = row.value("厂家序列号");
            if (!manufacturerSerialNumber.isBlank()) {
                String normalizedSerialNumber = manufacturerSerialNumber.toLowerCase(Locale.ROOT);
                if (!seenManufacturerSerialNumbers.add(normalizedSerialNumber)) {
                    add(errors, invalidRows, row, "厂家序列号", "文件内厂家序列号重复");
                } else if (assetRepository.existsByManufacturerSerialNumberIgnoreCase(manufacturerSerialNumber)) {
                    add(errors, invalidRows, row, "厂家序列号", "已被系统中的其他资产使用");
                }
            }

            validateDecimal(row, "采购价格(元)", errors, invalidRows);
            validateDecimal(row, "当前价值(元)", errors, invalidRows);
            validateRelatedDevices(row, errors, invalidRows);
            validateBindingTags(row, fileTags, errors, invalidRows);
            try {
                AssetRequest request = requestFor(row, 1L, 1L, 1L, 1L, 1L);
                validator.validate(request).forEach(violation ->
                        add(errors, invalidRows, row, violation.getPropertyPath().toString(), violation.getMessage()));
                for (String field : List.of("所属公司*", "资产分类*", "存放位置*")) {
                    if (row.value(field).length() > 200) add(errors, invalidRows, row, field, "不能超过200个字符");
                }
                if (modelValue(row).length() > 200) add(errors, invalidRows, row, "设备型号*", "不能超过200个字符");
                if (request.imageUrls() != null) {
                    request.imageUrls().forEach(imageStorage::validateReference);
                } else if (request.imageUrl() != null) {
                    imageStorage.validateReference(request.imageUrl());
                }
            } catch (ApiException exception) {
                add(errors, invalidRows, row, "资产字段", exception.getMessage());
            }

            String status = row.value("资产状态*");
            if (!status.isBlank() && findStatus(status).isEmpty()) {
                add(errors, invalidRows, row, "资产状态*", "必须使用系统固定状态：当前可用、已经领出、维护中或已报废");
            }
            if (isCheckedOutStatus(status) && row.value("领用人").isBlank()) {
                add(errors, invalidRows, row, "领用人", "资产状态为已经领出时必须填写领用人");
            }
            warnNewLookup(row, "所属公司*", LookupType.COMPANY, warnings);
            warnNewLookupValue(row, "设备型号*", modelValue(row), LookupType.MODEL, warnings);
            warnNewLookup(row, "资产分类*", LookupType.CATEGORY, warnings);
            warnNewLookup(row, "存放位置*", LookupType.LOCATION, warnings);
        }
        validateBindingGraph(rows, errors, invalidRows);
        return new ValidationResult(errors, warnings, invalidRows);
    }

    private AssetRequest requestFor(ImportRow row, Long company, Long model, Long category, Long status, Long location) {
        return new AssetRequest(row.value("资产编号*"), row.value("资产名称*"), clean(row.value("归属部门")),
                clean(row.value("CPU")), clean(row.value("内存")), clean(row.value("硬盘")), clean(row.value("显卡")),
                clean(row.value("厂家序列号")), clean(row.value("屏幕尺寸")), clean(row.value("分辨率")),
                clean(row.value("显示接口")), clean(row.value("订单号")), company, model, null, category, status, location,
                decimal(row.value("采购价格(元)"), row.rowNumber(), "采购价格(元)"),
                decimal(row.value("当前价值(元)"), row.rowNumber(), "当前价值(元)"),
                isCheckedOutStatus(row.value("资产状态*")), clean(row.value("领用人")), clean(row.value("图片地址")),
                imageUrls(row.value("图片地址(JSON)"), row.rowNumber()), clean(row.value("备注")), List.of(), null,
                relatedDevices(relatedDevicesValue(row), row.rowNumber()), List.of(),
                customParameters(row.value("自定义参数(JSON)"), row.rowNumber()), null);
    }

    private void validateBindingGraph(List<ImportRow> rows, List<RowMessage> errors, Set<Integer> invalidRows) {
        Map<String, AssetProfile> profiles = new HashMap<>();
        Map<String, String> owners = new HashMap<>();
        for (ImportRow row : rows) {
            String name = row.value("资产分类*");
            profiles.put(row.value("资产编号*"), lookupRepository.findByTypeAndNameIgnoreCase(LookupType.CATEGORY, name)
                    .map(value -> value.getAssetProfile() == null ? AssetProfile.infer(value.getName()) : value.getAssetProfile())
                    .orElseGet(() -> AssetProfile.infer(name)));
        }
        for (ImportRow row : rows) {
            List<String[]> edges = new ArrayList<>();
            for (String display : bindingTags(row.value("绑定显示器资产编号(分号分隔)"))) edges.add(new String[]{row.value("资产编号*"), display});
            if (!row.value("绑定电脑资产编号").isBlank()) edges.add(new String[]{row.value("绑定电脑资产编号"), row.value("资产编号*")});
            for (String[] edge : edges) {
                for (String tag : edge) {
                    if (!profiles.containsKey(tag)) assetRepository.findByAssetTagIgnoreCase(tag).ifPresent(asset -> {
                        var category = asset.getCategory();
                        profiles.put(tag, category.getAssetProfile() == null ? AssetProfile.infer(category.getName()) : category.getAssetProfile());
                        asset.getBoundComputers().stream().findFirst().ifPresent(computer -> owners.put(tag, computer.getAssetTag()));
                    });
                }
                if (profiles.get(edge[0]) != AssetProfile.COMPUTER || profiles.get(edge[1]) != AssetProfile.DISPLAY) {
                    add(errors, invalidRows, row, "设备绑定", "绑定关系必须是电脑绑定显示器");
                } else {
                    String owner = owners.putIfAbsent(edge[1], edge[0]);
                    if (owner != null && !owner.equals(edge[0])) add(errors, invalidRows, row, "设备绑定", "显示器“" + edge[1] + "”已绑定其他电脑");
                }
            }
        }
    }

    private void validateBindingTags(ImportRow row, Set<String> fileTags, List<RowMessage> errors, Set<Integer> invalidRows) {
        List<String> references = new ArrayList<>(bindingTags(row.value("绑定显示器资产编号(分号分隔)")));
        String computerTag = clean(row.value("绑定电脑资产编号"));
        if (computerTag != null) references.add(computerTag);
        for (String reference : references) {
            String field = computerTag != null && computerTag.equals(reference) ? "绑定电脑资产编号" : "绑定显示器资产编号(分号分隔)";
            if (!reference.matches("\\d{12}")) {
                add(errors, invalidRows, row, field, "绑定资产编号必须是12位数字");
            } else if (reference.equals(row.value("资产编号*"))) {
                add(errors, invalidRows, row, field, "资产不能绑定自身");
            } else if (!fileTags.contains(reference) && !assetRepository.existsByAssetTagIgnoreCase(reference)) {
                add(errors, invalidRows, row, field, "绑定资产编号“" + reference + "”不存在");
            }
        }
    }

    private List<String> bindingTags(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[;；]"))
                .map(String::trim).filter(tag -> !tag.isBlank()).distinct().toList();
    }

    private void warnNewLookup(ImportRow row, String header, LookupType type, List<RowMessage> warnings) {
        String value = row.value(header);
        warnNewLookupValue(row, header, value, type, warnings);
    }

    private void warnNewLookupValue(ImportRow row, String header, String value, LookupType type, List<RowMessage> warnings) {
        if (!value.isBlank() && lookupRepository.findByTypeAndNameIgnoreCase(type, value).isEmpty()) {
            warnings.add(new RowMessage(row.rowNumber(), header, "导入时将自动新建选项“" + value + "”"));
        }
    }

    private void validateDecimal(ImportRow row, String header, List<RowMessage> errors, Set<Integer> invalidRows) {
        String value = row.value(header);
        if (value.isBlank()) return;
        try {
            if (new BigDecimal(value).signum() < 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            add(errors, invalidRows, row, header, "必须是大于或等于0的数字");
        }
    }

    private void validateRelatedDevices(ImportRow row, List<RowMessage> errors, Set<Integer> invalidRows) {
        String value = relatedDevicesValue(row);
        if (value.isBlank()) return;
        try {
            relatedDevices(value, row.rowNumber());
        } catch (ApiException exception) {
            add(errors, invalidRows, row, "随附配件(JSON)", exception.getMessage());
        }
    }

    private List<AssetRequest.RelatedDeviceRequest> relatedDevices(String value, int rowNumber) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<AssetRequest.RelatedDeviceRequest> devices = objectMapper.readValue(value,
                    new TypeReference<List<AssetRequest.RelatedDeviceRequest>>() {});
            for (AssetRequest.RelatedDeviceRequest device : devices) {
                if (device.name() == null || device.name().isBlank() || device.quantity() < 1) {
                    throw new IllegalArgumentException();
                }
            }
            return devices;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "第 " + rowNumber + " 行随附配件 JSON 格式不正确");
        }
    }

    private List<AssetRequest.CustomParameterRequest> customParameters(String value, int rowNumber) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<AssetRequest.CustomParameterRequest> parameters = objectMapper.readValue(value,
                    new TypeReference<List<AssetRequest.CustomParameterRequest>>() {});
            if (parameters.size() > 5) throw new IllegalArgumentException();
            for (AssetRequest.CustomParameterRequest parameter : parameters) {
                if (parameter.name() == null || parameter.name().isBlank()
                        || parameter.value() == null || parameter.value().isBlank()) {
                    throw new IllegalArgumentException();
                }
            }
            return parameters;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "第 " + rowNumber + " 行自定义参数 JSON 格式不正确");
        }
    }

    private List<String> imageUrls(String value, int rowNumber) {
        if (value == null || value.isBlank()) return null;
        try {
            List<String> images = objectMapper.readValue(value, new TypeReference<List<String>>() {});
            if (images == null || images.size() > 5 || images.stream().anyMatch(image -> image == null || image.isBlank())) {
                throw new IllegalArgumentException();
            }
            return images;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "第 " + rowNumber + " 行图片地址 JSON 必须是最多5个非空地址组成的数组");
        }
    }

    private LookupValue resolveLookup(LookupType type, String name, boolean create, Set<String> created) {
        if (type == LookupType.STATUS) {
            return findStatus(name).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                    type.getLabel() + "选项“" + name + "”不存在"));
        }
        return lookupRepository.findByTypeAndNameIgnoreCase(type, name.trim()).orElseGet(() -> {
            if (!create) throw new ApiException(HttpStatus.BAD_REQUEST, type.getLabel() + "选项“" + name + "”不存在");
            LookupValue value = lookupRepository.save(new LookupValue(type, name.trim()));
            created.add(type.getLabel() + "：" + value.getName());
            return value;
        });
    }

    private Optional<LookupValue> findStatus(String name) {
        String clean = name == null ? "" : name.trim();
        Optional<LookupValue> exact = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, clean);
        if (exact.isPresent()) return exact;
        return switch (clean) {
            case "当前可用" -> lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用");
            case "已经领出" -> lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用");
            case "维护中" -> lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "维修中");
            default -> Optional.empty();
        };
    }

    private ParsedFile parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择 CSV 文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 文件不能超过5MB");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(file.getBytes())).toString();
        }
        catch (java.nio.charset.CharacterCodingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 编码不是 UTF-8，请另存为 CSV UTF-8 后再导入");
        }
        catch (IOException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "无法读取 CSV 文件"); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> records = parseCsv(text);
        if (records.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 文件没有表头");
        List<String> headers = records.getFirst().stream().map(String::trim).toList();
        Set<String> seenHeaders = new HashSet<>();
        for (String header : headers) {
            if (!header.isBlank() && !seenHeaders.add(header)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 表头重复：" + header);
            }
        }
        List<String> missing = REQUIRED_HEADERS.stream().filter(header -> !headers.contains(header)).toList();
        if (!headers.contains("设备型号*") && !headers.contains("电脑型号*")) {
            missing = new ArrayList<>(missing);
            missing.add("设备型号*");
        }
        if (!missing.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 缺少表头：" + String.join("、", missing));
        List<ImportRow> rows = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> values = records.get(index);
            if (values.stream().allMatch(String::isBlank)) continue;
            if (values.size() > headers.size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "第 " + (index + 1) + " 行列数超过表头，请检查逗号和双引号");
            }
            Map<String, String> data = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                data.put(headers.get(column), column < values.size() ? values.get(column).trim() : "");
            }
            rows.add(new ImportRow(index + 1, data));
        }
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 中没有资产数据");
        if (rows.size() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "单次最多导入2000项资产");
        return new ParsedFile(rows);
    }

    static List<List<String>> parseCsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else if (quoted) {
                    quoted = false;
                    closedQuote = true;
                } else if (field.isEmpty() && !closedQuote) {
                    quoted = true;
                } else {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 双引号位置错误，请使用双引号包围整个字段并将内部双引号写成两个双引号");
                }
            } else if (character == ',' && !quoted) {
                row.add(field.toString()); field.setLength(0);
                closedQuote = false;
            } else if ((character == '\n' || character == '\r') && !quoted) {
                if (character == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString()); field.setLength(0);
                records.add(row); row = new ArrayList<>();
                closedQuote = false;
            } else {
                if (closedQuote) throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 闭合双引号后只能是逗号或换行");
                field.append(character);
            }
        }
        if (quoted) throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 中存在未闭合的双引号");
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString()); records.add(row);
        }
        return records;
    }

    private BigDecimal decimal(String value, int row, String field) {
        if (value == null || value.isBlank()) return null;
        try { return new BigDecimal(value); }
        catch (NumberFormatException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "第 " + row + " 行“" + field + "”格式错误"); }
    }

    private boolean isCheckedOutStatus(String status) {
        return status != null && (status.contains("在用") || status.contains("领出"));
    }

    private String modelValue(ImportRow row) {
        String current = row.value("设备型号*");
        return current.isBlank() ? row.value("电脑型号*") : current;
    }

    private String relatedDevicesValue(ImportRow row) {
        String current = row.value("随附配件(JSON)");
        return current.isBlank() ? row.value("关联设备(JSON)") : current;
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private void add(List<RowMessage> errors, Set<Integer> invalidRows, ImportRow row, String field, String message) {
        errors.add(new RowMessage(row.rowNumber(), field, message)); invalidRows.add(row.rowNumber());
    }

    private record ParsedFile(List<ImportRow> rows) {}
    private record ImportRow(int rowNumber, Map<String, String> values) {
        String value(String header) { return values.getOrDefault(header, ""); }
    }
    private record ValidationResult(List<RowMessage> errors, List<RowMessage> warnings, Set<Integer> invalidRows) {}
    private record PendingBinding(Long assetId, List<String> displayTags, String computerTag) {}
    public record RowMessage(int row, String field, String message) {}
    public record PreviewRow(int row, String assetTag, String name, String status, boolean valid) {}
    public record PreviewResponse(int totalRows, int validRows, List<RowMessage> errors, List<RowMessage> warnings,
                                  List<PreviewRow> sample, boolean canImport) {}
    public record ImportResponse(int importedCount, int createdLookupCount, List<String> createdLookups) {}
}
