package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.AssetRepository;
import com.acme.assetmanagement.audit.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auditimportdb;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditAndImportTest {
    @Autowired MockMvc mockMvc;
    @Autowired AssetRepository assetRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired com.acme.assetmanagement.audit.AuditLogService auditService;
    @Autowired com.acme.assetmanagement.asset.AssetCsvTemplateService csvExportService;
    @Autowired com.acme.assetmanagement.user.UserRepository users;

    @Test
    void previewRejectsOverlongNameAndWrongBindingType() throws Exception {
        var longName = csv(bindingHeader() + "970000000001," + "名".repeat(161) + ",公司,型号,台式机,当前可用,仓库,,\n");
        mockMvc.perform(multipart("/api/assets/import/preview").file(longName).with(assetUser()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canImport").value(false));
        var wrongType = csv(bindingHeader() + "970000000001,电脑,公司,型号,台式机,当前可用,仓库,202609010001,\n");
        mockMvc.perform(multipart("/api/assets/import/preview").file(wrongType).with(assetUser()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canImport").value(false));
    }

    @Test
    void exportedUserInputIsTextRatherThanFormula() throws Exception {
        auditService.authenticationFailure("=1+1", "测试");
        String csv = mockMvc.perform(get("/api/audit-logs/export.csv").with(adminUser()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"'=1+1\""));
    }

    @Test
    void exportsEveryAssetAsCsvData() throws Exception {
        String csv = mockMvc.perform(get("/api/assets/export.csv").with(assetUser()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(csv.startsWith("\uFEFF资产编号*,资产名称*,所属公司*"));
        assetRepository.findAll().forEach(asset -> assertTrue(csv.contains(asset.getAssetTag())));
        assertTrue(auditLogRepository.findAll().stream().anyMatch(log ->
                "CSV_TEMPLATE_EXPORT".equals(log.getAction().name()) && log.getSummary().contains("导出授权范围资产数据")));
    }

    @Test
    void onlySuperAdminCanReadAuditLogs() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(assetUser())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit-logs").with(adminUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void rejectsMalformedCsvWithoutChangingData() throws Exception {
        long before = assetRepository.count();
        String header = "资产编号*,资产名称*,所属公司*,设备型号*,资产分类*,资产状态*,存放位置*\n";
        for (String name : java.util.List.of("坏\"名\"称", "\"名称\"多余")) {
            var file = csv(header + "980000000001," + name + ",公司,型号,台式机,当前可用,仓库\n");
            mockMvc.perform(multipart("/api/assets/import/preview").file(file).with(assetUser()).with(csrf()))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(multipart("/api/assets/import/commit").file(file).with(assetUser()).with(csrf()))
                    .andExpect(status().isBadRequest());
        }
        var invalidEncoding = new MockMultipartFile("file", "assets.csv", "text/csv",
                (header + "980000000001,电脑,公司,型号,台式机,当前可用,仓库\n")
                        .getBytes(java.nio.charset.Charset.forName("GBK")));
        mockMvc.perform(multipart("/api/assets/import/commit").file(invalidEncoding).with(assetUser()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("UTF-8")));
        org.junit.jupiter.api.Assertions.assertEquals(before, assetRepository.count());
    }

    @Test
    void importsQuotedFieldsWithCommasQuotesAndNewlines() throws Exception {
        var file = csv("资产编号*,资产名称*,所属公司*,设备型号*,资产分类*,资产状态*,存放位置*,备注\r\n"
                + "980000000002,\"电脑,\"\"备用\"\"\",公司,型号,台式机,当前可用,仓库,\"第一行\n第二行\"\r\n");
        mockMvc.perform(multipart("/api/assets/import/commit").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isOk());
        var asset = assetRepository.findByAssetTagIgnoreCase("980000000002").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("电脑,\"备用\"", asset.getName());
        org.junit.jupiter.api.Assertions.assertEquals("第一行\n第二行", asset.getNotes());
    }

    @Test
    void previewsAndImportsValidCsvAsOneTransaction() throws Exception {
        MockMultipartFile file = csv("""
                资产编号*,资产名称*,所属公司*,电脑型号*,资产分类*,资产状态*,存放位置*,CPU,内存,硬盘,显卡,厂家序列号,采购价格(元),当前价值(元),领用人,图片地址,备注,关联设备(JSON),自定义参数(JSON)
                202600000099,导入测试电脑,新公司,新型号,笔记本电脑,当前可用,新仓库,i7,16G,512G,,CSV-SN-001,6999,6200,,,,"[{""name"":""鼠标"",""quantity"":1}]","[{""name"":""管理IP"",""value"":""192.168.1.20""}]"
                """);

        mockMvc.perform(multipart("/api/assets/import/preview").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canImport").value(true))
                .andExpect(jsonPath("$.totalRows").value(1));

        mockMvc.perform(multipart("/api/assets/import/commit").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(1));

        assertTrue(assetRepository.existsByAssetTagIgnoreCase("202600000099"));
        assertTrue(assetRepository.findAll().stream().anyMatch(asset -> "CSV-SN-001".equals(asset.getManufacturerSerialNumber())));
        assertTrue(assetRepository.findByAssetTagIgnoreCase("202600000099").orElseThrow().getCustomParameters().stream()
                .anyMatch(parameter -> "管理IP".equals(parameter.getName()) && "192.168.1.20".equals(parameter.getValue())));
        assertTrue(auditLogRepository.findAll().stream().anyMatch(log -> "CSV_IMPORT".equals(log.getAction().name())));
    }

    @Test
    void rejectsCsvWithExistingAssetNumberWithoutWritingData() throws Exception {
        long before = assetRepository.count();
        MockMultipartFile file = csv("""
                资产编号*,资产名称*,所属公司*,电脑型号*,资产分类*,资产状态*,存放位置*
                202609010001,重复编号电脑,示例科技有限公司,ThinkPad T14 Gen 5,笔记本电脑,当前可用,总部 · 3楼设备间
                """);

        mockMvc.perform(multipart("/api/assets/import/preview").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canImport").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("资产编号*"));

        mockMvc.perform(multipart("/api/assets/import/commit").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(before, assetRepository.count());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "true,false,false", "true,false,true", "false,true,false", "false,true,true",
            "true,true,false", "true,true,true"
    })
    void importsBindingsRegardlessOfRowOrder(boolean computerDeclares, boolean displayDeclares,
                                            boolean reverse) throws Exception {
        String computer = "940000000001,导入电脑,导入公司,电脑型号,台式机,当前可用,导入仓库,"
                + (computerDeclares ? "940000000002" : "") + ",\n";
        String display = "940000000002,导入显示器,导入公司,显示器型号,显示器,当前可用,导入仓库,,"
                + (displayDeclares ? "940000000001" : "") + "\n";
        MockMultipartFile file = csv(bindingHeader() + (reverse ? display + computer : computer + display));
        mockMvc.perform(multipart("/api/assets/import/commit").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.importedCount").value(2));
        entityManager.flush();
        entityManager.clear();
        var importedComputer = assetRepository.findByAssetTagIgnoreCase("940000000001").orElseThrow();
        var importedDisplay = assetRepository.findByAssetTagIgnoreCase("940000000002").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(1, importedComputer.getBoundDisplays().size());
        org.junit.jupiter.api.Assertions.assertEquals(importedDisplay.getId(),
                importedComputer.getBoundDisplays().iterator().next().getId());
        org.junit.jupiter.api.Assertions.assertEquals(1, importedDisplay.getBoundComputers().size());
        org.junit.jupiter.api.Assertions.assertEquals(importedComputer.getId(),
                importedDisplay.getBoundComputers().iterator().next().getId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void conflictingImportBindingsRollBackAllAssets(boolean reverse) throws Exception {
        long before = assetRepository.count();
        long auditBefore = auditLogRepository.count();
        String computer = "950000000001,冲突电脑A,冲突公司,电脑型号,台式机,当前可用,冲突仓库,950000000003,\n";
        String otherComputer = "950000000002,冲突电脑B,冲突公司,电脑型号,台式机,当前可用,冲突仓库,,\n";
        String display = "950000000003,冲突显示器,冲突公司,显示器型号,显示器,当前可用,冲突仓库,,950000000002\n";
        MockMultipartFile file = csv(bindingHeader()
                + (reverse ? display + otherComputer + computer : computer + otherComputer + display));
        mockMvc.perform(multipart("/api/assets/import/preview").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canImport").value(false));
        mockMvc.perform(multipart("/api/assets/import/commit").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(before, assetRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals(auditBefore, auditLogRepository.count());
        org.junit.jupiter.api.Assertions.assertFalse(assetRepository.existsByAssetTagIgnoreCase("950000000001"));
    }

    @Test
    void exportedImagesSurviveCsvImport() throws Exception {
        var asset = assetRepository.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        var images = java.util.List.of("https://example.com/front.png", "https://example.com/back.png");
        asset.setImageUrls(images);
        asset.setImageUrl(images.getFirst());
        assetRepository.saveAndFlush(asset);
        String exported = new String(csvExportService.createAssetExport(java.util.List.of(
                com.acme.assetmanagement.asset.AssetResponse.from(asset))), StandardCharsets.UTF_8);
        assertTrue(exported.contains(images.get(1)), "导出必须保留第二张图片");
        exported = exported.replace(asset.getAssetTag(), "980000000002");
        if (asset.getManufacturerSerialNumber() != null) exported = exported.replace(asset.getManufacturerSerialNumber(), "");
        mockMvc.perform(multipart("/api/assets/import/commit").file(csv(exported.substring(1))).with(assetUser()).with(csrf()))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        org.junit.jupiter.api.Assertions.assertEquals(images,
                assetRepository.findByAssetTagIgnoreCase("980000000002").orElseThrow().getImageUrls());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"duplicateHeader", "extraCell"})
    void rejectsAmbiguousCsvColumnsBeforeImport(String variant) throws Exception {
        String header = "资产编号*,资产名称*,所属公司*,设备型号*,资产分类*,资产状态*,存放位置*";
        String row = "980000000003,测试电脑,测试公司,型号,台式机,当前可用,仓库";
        if (variant.equals("duplicateHeader")) header += ",资产名称*";
        var file = csv(header + "\n" + row + ",被静默覆盖的数据\n");
        long before = assetRepository.count();
        mockMvc.perform(multipart("/api/assets/import/preview").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/assets/import/commit").file(file).with(assetUser()).with(csrf()))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(before, assetRepository.count());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"action", "result"})
    void unknownAuditFilterReturnsNoMatches(String field) throws Exception {
        mockMvc.perform(get("/api/audit-logs").param(field, "UNKNOWN").with(adminUser()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/audit-logs/export.csv").param(field, "UNKNOWN").with(adminUser()))
                .andExpect(status().isOk());
    }

    private static String bindingHeader() {
        return "资产编号*,资产名称*,所属公司*,设备型号*,资产分类*,资产状态*,存放位置*,绑定显示器资产编号(分号分隔),绑定电脑资产编号\n";
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "assets.csv", "text/csv",
                ("\uFEFF" + content).getBytes(StandardCharsets.UTF_8));
    }

    private RequestPostProcessor assetUser() {
        var account = users.findByUsernameIgnoreCase("asset-tester").orElseGet(() -> {
            var created = new com.acme.assetmanagement.user.UserAccount();
            created.setUsername("asset-tester"); created.setDisplayName("测试用户"); created.setPasswordHash("test-hash");
            return users.saveAndFlush(created);
        });
        return user(com.acme.assetmanagement.security.AccountPrincipal.from(account));
    }

    private static RequestPostProcessor adminUser() {
        return user("admin").authorities(
                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),
                new SimpleGrantedAuthority("ASSET_VIEW"));
    }
}
