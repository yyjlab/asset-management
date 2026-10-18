package com.acme.assetmanagement;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.acme.assetmanagement.lookup.LookupRepository;
import com.acme.assetmanagement.lookup.LookupType;
import com.acme.assetmanagement.lookup.LookupValue;
import com.acme.assetmanagement.lookup.AssetProfile;
import com.acme.assetmanagement.asset.AssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "app.asset-images.directory=${java.io.tmpdir}/asset-management-test-images"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AssetApiTest {
    @Autowired MockMvc mockMvc;
    @Autowired LookupRepository lookupRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired com.acme.assetmanagement.audit.AuditLogRepository auditLogRepository;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired com.acme.assetmanagement.user.UserRepository users;

    @Test
    void categoryTemplateRemembersNamesAndOrderWithoutChangingOtherAssets() throws Exception {
        var seed = assetRepository.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        var category = lookupRepository.saveAndFlush(new LookupValue(LookupType.CATEGORY, "模板测试网络", AssetProfile.GENERAL));
        var otherCategory = lookupRepository.saveAndFlush(new LookupValue(LookupType.CATEGORY, "模板测试其他", AssetProfile.GENERAL));
        long categoryId = category.getId();
        long otherCategoryId = otherCategory.getId();
        String template = """
                {"assetTag":"%s","name":"模板测试","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":false,"assignedTo":"测试人",
                 "customParameters":%s}
                """;
        String first = template.formatted("960000009001", seed.getCompany().getId(), seed.getModel().getId(),
                categoryId, seed.getStatus().getId(), seed.getLocation().getId(),
                "[{\"name\":\"网口\",\"value\":\"48\"},{\"name\":\"连接接口\",\"value\":\"47\"}]");
        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category.parameterTemplate[0]").value("网口"));
        String second = template.formatted("960000009002", seed.getCompany().getId(), seed.getModel().getId(),
                categoryId, seed.getStatus().getId(), seed.getLocation().getId(),
                "[{\"name\":\"连接接口\",\"value\":\"23\"},{\"name\":\"网口\",\"value\":\"24\"}]");
        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();
        assertEquals(java.util.List.of("连接接口", "网口"), lookupRepository.findById(categoryId).orElseThrow().getParameterTemplate());
        assertTrue(lookupRepository.findById(otherCategoryId).orElseThrow().getParameterTemplate().isEmpty());
        var original = assetRepository.findByAssetTagIgnoreCase("960000009001").orElseThrow();
        assertEquals("网口", original.getCustomParameters().get(0).getName());
        assertEquals("48", original.getCustomParameters().get(0).getValue());
        mockMvc.perform(put("/api/assets/{id}", original.getId()).with(assetUser()).with(csrf())
                        .header("If-Match", original.getVersion()).contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertEquals(java.util.List.of("网口", "连接接口"), lookupRepository.findById(categoryId).orElseThrow().getParameterTemplate());
        mockMvc.perform(get("/api/lookups").with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '模板测试网络')].parameterTemplate[0]").value(hasItem("网口")));
    }

    @Test
    void editingAndReturningUnavailableEquipmentPreservesItsLifecycleStatus() throws Exception {
        var asset = assetRepository.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        for (String name : java.util.List.of("维修中", "已报废")) {
            asset.setStatus(lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, name).orElseThrow());
            asset.setCheckedOut(true);
            asset.setAssignedTo("待交还员工");
            asset.setAssignedUserId(123L);
            assetRepository.saveAndFlush(asset);
            String body = """
                    {"assetTag":"%s","name":"维修记录补充","companyId":%d,"modelId":%d,
                     "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":true,
                     "assignedTo":"待交还员工","assignedUserId":123,"notes":"仅补充备注"}
                    """.formatted(asset.getAssetTag(), asset.getCompany().getId(), asset.getModel().getId(),
                    asset.getCategory().getId(), asset.getStatus().getId(), asset.getLocation().getId());
            mockMvc.perform(put("/api/assets/{id}", asset.getId()).with(assetUser()).with(csrf())
                            .header("If-Match", asset.getVersion()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.checkedOut").value(true))
                    .andExpect(jsonPath("$.assignedUserId").value(123)).andExpect(jsonPath("$.status.name").value(name));
            String endpoint = "维修中".equals(name) ? "/api/assets/" + asset.getId() + "/return"
                    : "/api/assets/qr/" + asset.getQrToken() + "/return";
            mockMvc.perform(post(endpoint).with(assetUser()).with(csrf()).header("If-Match", asset.getVersion()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.checkedOut").value(false))
                    .andExpect(jsonPath("$.assignedUserId").doesNotExist()).andExpect(jsonPath("$.status.name").value(name));
        }
    }

    @Test
    void staleQuickReturnsCannotClearANewAssigneeThroughEitherEndpoint() throws Exception {
        var asset = assetRepository.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        asset.setStatus(lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用").orElseThrow());
        asset.setCheckedOut(true);
        asset.setAssignedTo("原领用人");
        assetRepository.saveAndFlush(asset);
        long oldVersion = asset.getVersion();
        asset.setAssignedTo("新领用人");
        asset.setAssignedUserId(123L);
        assetRepository.saveAndFlush(asset);
        for (String endpoint : java.util.List.of("/api/assets/" + asset.getId() + "/return",
                "/api/assets/qr/" + asset.getQrToken() + "/return")) {
            mockMvc.perform(post(endpoint).with(assetUser()).with(csrf()).header("If-Match", oldVersion))
                    .andExpect(status().isConflict());
            mockMvc.perform(post(endpoint).with(assetUser()).with(csrf()))
                    .andExpect(status().isPreconditionRequired());
            assertTrue(asset.isCheckedOut());
            assertEquals("新领用人", asset.getAssignedTo());
            assertEquals(123L, asset.getAssignedUserId());
        }
    }

    @Test
    void staleEditCannotUndoReturn() throws Exception {
        var asset = assetRepository.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        asset.setStatus(lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用").orElseThrow());
        asset.setCheckedOut(true);
        asset.setAssignedTo("旧领用人");
        assetRepository.saveAndFlush(asset);
        long version = asset.getVersion();
        String body = """
                {"assetTag":"%s","name":"过期修改","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":true,"assignedTo":"旧领用人"}
                """.formatted(asset.getAssetTag(), asset.getCompany().getId(), asset.getModel().getId(),
                asset.getCategory().getId(), asset.getStatus().getId(), asset.getLocation().getId());
        mockMvc.perform(post("/api/assets/{id}/return", asset.getId()).with(assetUser()).with(csrf()).header("If-Match", version))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/assets/{id}", asset.getId()).with(assetUser()).with(csrf())
                        .header("If-Match", version).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertFalse(assetRepository.findById(asset.getId()).orElseThrow().isCheckedOut());
        mockMvc.perform(put("/api/assets/{id}", asset.getId()).with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void sharedImageSurvivesUntilLastAssetIsDeleted() throws Exception {
        var seed = assetRepository.findById(assetRepository.findByAssetTagIgnoreCase("202609010001").orElseThrow().getId()).orElseThrow();
        String image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        String template = """
                {"assetTag":"%s","name":"图片生命周期","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":false,"imageUrls":["%s"]}
                """;
        String first = template.formatted("960000000001", seed.getCompany().getId(), seed.getModel().getId(),
                seed.getCategory().getId(), seed.getStatus().getId(), seed.getLocation().getId(), image);
        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated());
        var original = assetRepository.findByAssetTagIgnoreCase("960000000001").orElseThrow();
        String reference = original.getImageUrl();
        String second = template.formatted("960000000002", seed.getCompany().getId(), seed.getModel().getId(),
                seed.getCategory().getId(), seed.getStatus().getId(), seed.getLocation().getId(), reference);
        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isCreated());
        var clone = assetRepository.findByAssetTagIgnoreCase("960000000002").orElseThrow();
        mockMvc.perform(delete("/api/assets/{id}", original.getId()).with(assetUser()).with(csrf())).andExpect(status().isNoContent());
        mockMvc.perform(get(reference).with(assetUser())).andExpect(status().isOk());
        mockMvc.perform(delete("/api/assets/{id}", clone.getId()).with(assetUser()).with(csrf())).andExpect(status().isNoContent());
        mockMvc.perform(get(reference).with(assetUser())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isNotFound());
        assertFalse(assetRepository.existsByAssetTagIgnoreCase("960000000002"));
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "asset-management-test-images", reference.substring(reference.lastIndexOf('/') + 1))));
    }

    @Test
    void listsSeedAsset() throws Exception {
        mockMvc.perform(get("/api/assets").with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].assetTag", hasItem("202609010001")))
                .andExpect(jsonPath("$[?(@.assetTag == '202609010001')].cpu", hasItem("Intel Core Ultra 7 155U")))
                .andExpect(jsonPath("$[?(@.assetTag == '202609010001')].memory", hasItem("32GB DDR5")))
                .andExpect(jsonPath("$[?(@.assetTag == '202609010001')].storage", hasItem("1TB NVMe SSD")))
                .andExpect(jsonPath("$[?(@.assetTag == '202609010001')].graphicsCard", hasItem("Intel Graphics 集成显卡")))
                .andExpect(jsonPath("$[?(@.assetTag == '202609010001')].createdAt").exists())
                .andExpect(jsonPath("$[?(@.assetTag == '202609010001')].updatedAt").exists());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "\"assetTag\":null,", "\"assetTag\":\"\","})
    void rejectsUpdateWithoutAssetTag(String tagField) throws Exception {
        var asset = assetRepository.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        String originalName = asset.getName();
        String body = """
                {%s"name":"不应保存的名称","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":false}
                """.formatted(tagField, asset.getCompany().getId(), asset.getModel().getId(),
                asset.getCategory().getId(), asset.getStatus().getId(), asset.getLocation().getId());
        mockMvc.perform(put("/api/assets/{id}", asset.getId()).with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("更新资产时资产编号不能为空"));
        assertEquals(originalName, assetRepository.findById(asset.getId()).orElseThrow().getName());
    }

    @Test
    void qrAssetRecordRequiresAuthenticationAndHidesSensitiveFields() throws Exception {
        var asset = assetRepository.findAll().stream()
                .filter(item -> "202609010001".equals(item.getAssetTag()))
                .findFirst().orElseThrow();

        mockMvc.perform(get("/api/public/assets/{qrToken}", asset.getQrToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/public/assets/{qrToken}", asset.getQrToken()).with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetTag").value("202609010001"))
                .andExpect(jsonPath("$.computerModel").isNotEmpty())
                .andExpect(jsonPath("$.status").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.purchasePrice").doesNotExist())
                .andExpect(jsonPath("$.currentValue").doesNotExist())
                .andExpect(jsonPath("$.notes").doesNotExist())
                .andExpect(jsonPath("$.qrToken").doesNotExist());

        mockMvc.perform(get("/api/public/assets/not-a-real-token").with(assetUser()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadsUtf8CsvWithAllAssetData() throws Exception {
        byte[] csv = mockMvc.perform(get("/api/assets/export.csv").with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("asset-data.csv")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(csv.length > 3);
        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.contains("资产编号*,资产名称*,所属公司*,归属部门"));
        assertTrue(text.contains("显卡,厂家序列号,屏幕尺寸,分辨率,显示接口,订单号,采购价格(元)"));
        assertTrue(text.contains("随附配件(JSON)"));
        assertTrue(text.contains("自定义参数(JSON)"));
        assertFalse(text.contains("关联设备(JSON)"));
        assetRepository.findAll().forEach(asset -> assertTrue(text.contains(asset.getAssetTag())));
        assertEquals(assetRepository.count() + 1, text.lines().count());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "https://asset.example.test/?from=login", "https://asset.example.test/#assets",
            "https://user:password@asset.example.test"
    })
    void rejectsQrBaseUrlsThatWouldBreakScanLinks(String url) throws Exception {
        mockMvc.perform(put("/api/settings/qr").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrBaseUrl\":\"https://asset.example.test\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/settings/qr").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrBaseUrl\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/settings/qr").with(adminUser()))
                .andExpect(jsonPath("$.qrBaseUrl").value("https://asset.example.test"));
    }

    @Test
    void generatesQrCodePointingToLatestAssetRecord() throws Exception {
        mockMvc.perform(get("/api/settings/qr").with(assetUser()))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/settings/qr").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrBaseUrl\":\"http://asset.example.test/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrBaseUrl").value("http://asset.example.test"))
                .andExpect(jsonPath("$.source").value("DATABASE"));

        String assets = mockMvc.perform(get("/api/assets/1").with(assetUser()))
                .andReturn().getResponse().getContentAsString();
        String qrToken = assets.replaceFirst("(?s).*?\\\"qrToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
        byte[] png = mockMvc.perform(get("/api/assets/1/qr").with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();

        BufferedImage labelImage = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(480, labelImage.getWidth());
        assertEquals(400, labelImage.getHeight());
        String target = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(labelImage)))).getText();
        assertEquals("http://asset.example.test/?qr=" + qrToken, target);
    }

    @Test
    void automaticallyGeneratesSequentialTwelveDigitAssetTags() throws Exception {
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long modelId = lookupRepository.findByTypeOrderByNameAsc(LookupType.MODEL).getFirst().getId();
        long categoryId = lookupRepository.findByTypeOrderByNameAsc(LookupType.CATEGORY).getFirst().getId();
        long statusId = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用").orElseThrow().getId();
        long locationId = lookupRepository.findByTypeOrderByNameAsc(LookupType.LOCATION).getFirst().getId();
        String body = """
                {"assetTag":"","name":"自动编号测试资产","ownershipDepartment":"研发部",
                 "companyId":%d,"modelId":%d,"categoryId":%d,"statusId":%d,"locationId":%d,
                 "checkedOut":false}
                """.formatted(companyId, modelId, categoryId, statusId, locationId);

        String first = mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownershipDepartment").value("研发部"))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("自动编号测试资产", "自动编号测试资产二")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String prefix = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().replace("-", "");
        String firstTag = first.replaceFirst("(?s).*?\\\"assetTag\\\":\\\"(\\d{12})\\\".*", "$1");
        String secondTag = second.replaceFirst("(?s).*?\\\"assetTag\\\":\\\"(\\d{12})\\\".*", "$1");
        assertTrue(firstTag.startsWith(prefix));
        assertTrue(secondTag.startsWith(prefix));
        assertEquals(Integer.parseInt(firstTag.substring(8)) + 1, Integer.parseInt(secondTag.substring(8)));
    }

    @Test
    void deletesUnusedLookupButProtectsOptionsUsedByAssets() throws Exception {
        String created = mockMvc.perform(post("/api/lookups").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CATEGORY\",\"name\":\"临时分类\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long lookupId = Long.parseLong(created.replaceFirst("^\\{\"id\":(\\d+).*", "$1"));

        mockMvc.perform(delete("/api/lookups/{id}", lookupId).with(assetUser()).with(csrf()))
                .andExpect(status().isNoContent());

        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        mockMvc.perform(delete("/api/lookups/{id}", companyId).with(assetUser()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("正在被")));
    }

    @Test
    void manuallyDeletesAssetNameSuggestionWithoutDeletingExistingAsset() throws Exception {
        var asset = assetRepository.findAll().stream()
                .filter(item -> "202609010001".equals(item.getAssetTag()))
                .findFirst().orElseThrow();
        var suggestion = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.ASSET_NAME, asset.getName())
                .orElseThrow();

        mockMvc.perform(delete("/api/lookups/{id}", suggestion.getId()).with(assetUser()).with(csrf()))
                .andExpect(status().isNoContent());

        assertTrue(lookupRepository.findByTypeAndNameIgnoreCase(LookupType.ASSET_NAME, asset.getName()).isEmpty());
        assertEquals(asset.getName(), assetRepository.findById(asset.getId()).orElseThrow().getName());
    }

    @Test
    void protectsSystemStatusOptionsFromCreationAndDeletion() throws Exception {
        assertTrue(lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "已报废").isPresent());

        mockMvc.perform(post("/api/lookups").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"STATUS\",\"name\":\"待报废\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("资产状态是系统固定选项，不能新建"));

        long statusId = lookupRepository.findByTypeOrderByNameAsc(LookupType.STATUS).getFirst().getId();
        mockMvc.perform(delete("/api/lookups/{id}", statusId).with(assetUser()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("资产状态是系统固定选项，不能删除"));
    }

    @Test
    void countsScrappedAssetsSeparatelyFromAvailableAssets() throws Exception {
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long modelId = lookupRepository.findByTypeOrderByNameAsc(LookupType.MODEL).getFirst().getId();
        long categoryId = lookupRepository.findByTypeOrderByNameAsc(LookupType.CATEGORY).getFirst().getId();
        long statusId = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "已报废").orElseThrow().getId();
        long locationId = lookupRepository.findByTypeOrderByNameAsc(LookupType.LOCATION).getFirst().getId();
        String body = """
                {"assetTag":"202600000088","name":"报废测试电脑","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":true,"assignedTo":"历史领用人"}
                """.formatted(companyId, modelId, categoryId, statusId, locationId);

        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status.name").value("已报废"))
                .andExpect(jsonPath("$.checkedOut").value(false))
                .andExpect(jsonPath("$.assignedTo").doesNotExist());

        mockMvc.perform(get("/api/assets/summary").with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scrapped").value(1))
                .andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.checkedOut").value(0));
    }

    @Test
    void updatesAssetWithUnifiedRelatedDevicesAndNoLegacyAccessoryRows() throws Exception {
        long assetId = assetRepository.findAll().stream()
                .filter(asset -> "202609010001".equals(asset.getAssetTag()))
                .findFirst().orElseThrow().getId();
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long modelId = lookupRepository.findByTypeOrderByNameAsc(LookupType.MODEL).getFirst().getId();
        long categoryId = lookupRepository.findByTypeOrderByNameAsc(LookupType.CATEGORY).getFirst().getId();
        long statusId = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用").orElseThrow().getId();
        long locationId = lookupRepository.findByTypeOrderByNameAsc(LookupType.LOCATION).getFirst().getId();
        String body = """
                {"assetTag":"202609010001","name":"更新后的资产","companyId":%d,"modelId":%d,
                 "modelName":"ThinkPad T14 Gen 5","categoryId":%d,"statusId":%d,"locationId":%d,
                 "cpu":"i7","memory":"32G","storage":"1T","checkedOut":false,
                 "imageUrl":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                 "imageUrls":["data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=","data:IMAGE/PNG;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="],"notes":"更新测试",
                 "relatedDevices":[{"name":"鼠标","model":"M90","specification":"USB","quantity":1}],
                 "accessories":[]}
                """.formatted(companyId, modelId, categoryId, statusId, locationId);

        mockMvc.perform(put("/api/assets/{id}", assetId).header("If-Match", assetRepository.findById(assetId).orElseThrow().getVersion()).with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("更新后的资产"))
                .andExpect(jsonPath("$.imageUrl", startsWith("/api/public/asset-images/")))
                .andExpect(jsonPath("$.imageUrls.length()").value(2))
                .andExpect(jsonPath("$.imageUrls[1]", startsWith("/api/public/asset-images/")))
                .andExpect(jsonPath("$.relatedDevices[0].name").value("鼠标"))
                .andExpect(jsonPath("$.accessories").isEmpty());

        String storedImageUrl = assetRepository.findById(assetId).orElseThrow().getImageUrls().getFirst();
        assertTrue(storedImageUrl.startsWith("/api/public/asset-images/"));
        assertFalse(storedImageUrl.startsWith("data:"));
        mockMvc.perform(get(storedImageUrl).with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));

        assertTrue(lookupRepository.findByTypeAndNameIgnoreCase(LookupType.CPU, "i7").isPresent());

        mockMvc.perform(put("/api/assets/{id}", assetId)
                        .header("If-Match", assetRepository.findById(assetId).orElseThrow().getVersion())
                        .with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("M90", "M100").replace("更新测试", "再次更新")))
                .andExpect(status().isOk());
        String changes = auditLogRepository.findAll().stream()
                .filter(log -> log.getAction() == com.acme.assetmanagement.audit.AuditAction.ASSET_UPDATE
                        && Long.valueOf(assetId).equals(log.getTargetId()))
                .max(java.util.Comparator.comparing(com.acme.assetmanagement.audit.AuditLog::getId))
                .orElseThrow().getChangesJson();
        assertTrue(changes.contains("M90") && changes.contains("M100"));
        assertTrue(changes.contains("更新测试") && changes.contains("再次更新"));
        assertTrue(changes.contains("/api/public/asset-images/"));

        String changedNumber = body.replace("202609010001", "202609010099");
        mockMvc.perform(put("/api/assets/{id}", assetId).header("If-Match", assetRepository.findById(assetId).orElseThrow().getVersion()).with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(changedNumber))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("编号不能修改")));

        String tooManyImages = body.replace(
                "\"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=\",\"data:IMAGE/PNG;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=\"",
                "\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"");
        mockMvc.perform(put("/api/assets/{id}", assetId).header("If-Match", assetRepository.findById(assetId).orElseThrow().getVersion()).with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(tooManyImages))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("资产图片最多上传5张"));
    }

    @Test
    void createsGeneralAssetWithFreeFormCustomParameters() throws Exception {
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long modelId = lookupRepository.findByTypeOrderByNameAsc(LookupType.MODEL).getFirst().getId();
        long categoryId = lookupRepository.save(new LookupValue(LookupType.CATEGORY, "交换机", AssetProfile.GENERAL)).getId();
        long statusId = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用").orElseThrow().getId();
        long locationId = lookupRepository.findByTypeOrderByNameAsc(LookupType.LOCATION).getFirst().getId();
        String body = """
                {"assetTag":"202600000088","name":"3楼接入交换机","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":false,"orderNumber":"PO-2026-0915",
                 "customParameters":[{"name":"管理IP","value":"192.168.10.20"},{"name":"端口数","value":"24口"}]}
                """.formatted(companyId, modelId, categoryId, statusId, locationId);

        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value("PO-2026-0915"))
                .andExpect(jsonPath("$.customParameters[0].name").value("管理IP"))
                .andExpect(jsonPath("$.customParameters[0].value").value("192.168.10.20"))
                .andExpect(jsonPath("$.customParameters[1].name").value("端口数"));

        var stored = assetRepository.findByAssetTagIgnoreCase("202600000088").orElseThrow();
        assertEquals(2, stored.getCustomParameters().size());
        assertEquals("24口", stored.getCustomParameters().get(1).getValue());

        String csv = mockMvc.perform(get("/api/assets/export.csv").with(assetUser()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.contains("自定义参数(JSON)"));
        assertTrue(csv.contains("管理IP"));
        assertTrue(csv.contains("192.168.10.20"));

        String tooManyParameters = """
                {"assetTag":"202600000089","name":"参数超限设备","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":false,
                 "customParameters":[{"name":"参数1","value":"1"},{"name":"参数2","value":"2"},
                 {"name":"参数3","value":"3"},{"name":"参数4","value":"4"},
                 {"name":"参数5","value":"5"},{"name":"参数6","value":"6"}]}
                """.formatted(companyId, modelId, categoryId, statusId, locationId);
        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(tooManyParameters))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("自定义参数最多5项"));
    }

    @Test
    void createsComputerModelFromEditableInput() throws Exception {
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long categoryId = lookupRepository.findByTypeOrderByNameAsc(LookupType.CATEGORY).getFirst().getId();
        long statusId = lookupRepository.findByTypeOrderByNameAsc(LookupType.STATUS).getFirst().getId();
        long locationId = lookupRepository.findByTypeOrderByNameAsc(LookupType.LOCATION).getFirst().getId();
        String body = """
                {"assetTag":"202600000009","name":"自定义型号电脑","companyId":%d,
                 "modelName":"Framework Laptop 16","categoryId":%d,"statusId":%d,"locationId":%d,
                 "memory":"64G","checkedOut":false}
                """.formatted(companyId, categoryId, statusId, locationId);

        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.model.name").value("Framework Laptop 16"))
                .andExpect(jsonPath("$.memory").value("64G"));
    }

    @Test
    void storesSearchesAndProtectsUniqueManufacturerSerialNumber() throws Exception {
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long modelId = lookupRepository.findByTypeOrderByNameAsc(LookupType.MODEL).getFirst().getId();
        long categoryId = lookupRepository.findByTypeOrderByNameAsc(LookupType.CATEGORY).getFirst().getId();
        long statusId = lookupRepository.findByTypeOrderByNameAsc(LookupType.STATUS).getFirst().getId();
        long locationId = lookupRepository.findByTypeOrderByNameAsc(LookupType.LOCATION).getFirst().getId();
        String body = "{\"assetTag\":\"202600000066\",\"name\":\"序列号测试电脑\","
                + "\"companyId\":" + companyId + ",\"modelId\":" + modelId + ","
                + "\"categoryId\":" + categoryId + ",\"statusId\":" + statusId + ","
                + "\"locationId\":" + locationId + ",\"manufacturerSerialNumber\":\"PF-ABC-001\","
                + "\"checkedOut\":false}";

        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.manufacturerSerialNumber").value("PF-ABC-001"));

        mockMvc.perform(get("/api/assets").param("search", "pf-abc-001").with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assetTag").value("202600000066"));

        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("202600000066", "202600000067").replace("序列号测试电脑", "重复序列号电脑")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("已被其他资产使用")));
    }

    @Test
    void searchesAssetsByAssignedTo() throws Exception {
        var asset = assetRepository.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        asset.setStatus(lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用").orElseThrow());
        asset.setCheckedOut(true);
        asset.setAssignedTo("邓福平");
        assetRepository.saveAndFlush(asset);

        mockMvc.perform(get("/api/assets").param("search", "福平").with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assetTag").value(asset.getAssetTag()));
    }

    @Test
    void createsLookupAndRejectsDuplicate() throws Exception {
        String body = "{\"type\":\"LOCATION\",\"name\":\"北京仓库\"}";
        mockMvc.perform(post("/api/lookups").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("北京仓库"));

        mockMvc.perform(post("/api/lookups").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsWrongLookupType() throws Exception {
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long modelId = lookupRepository.findByTypeOrderByNameAsc(LookupType.MODEL).getFirst().getId();
        long categoryId = lookupRepository.findByTypeOrderByNameAsc(LookupType.CATEGORY).getFirst().getId();
        long statusId = lookupRepository.findByTypeOrderByNameAsc(LookupType.STATUS).getFirst().getId();
        String body = """
                {"assetTag":"202600000002","name":"测试资产","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":false}
                """.formatted(companyId, modelId, categoryId, statusId, companyId);

        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("位置选项类型不正确"));
    }

    @Test
    void returnsCheckedOutAssetAndClearsAssignee() throws Exception {
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long modelId = lookupRepository.findByTypeOrderByNameAsc(LookupType.MODEL).getFirst().getId();
        long categoryId = lookupRepository.findByTypeOrderByNameAsc(LookupType.CATEGORY).getFirst().getId();
        long statusId = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用").orElseThrow().getId();
        long locationId = lookupRepository.findByTypeOrderByNameAsc(LookupType.LOCATION).getFirst().getId();
        String body = """
                {"assetTag":"202600000003","name":"待归还电脑","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":true,
                 "assignedTo":"张三",
                 "relatedDevices":[{"name":"显示器","model":"Dell U2723QE","serialNumber":"MONITOR-001","orderNumber":"PO-2026-0819","specification":"27英寸 4K","quantity":2}],
                 "accessories":[{"name":"鼠标","specification":"罗技 M90","quantity":1},{"name":"充电器","specification":"65W Type-C","quantity":1}]}
                """.formatted(companyId, modelId, categoryId, statusId, locationId);

        String created = mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedTo").value("张三"))
                .andExpect(jsonPath("$.relatedDevices[0].quantity").value(2))
                .andExpect(jsonPath("$.relatedDevices[0].orderNumber").value("PO-2026-0819"))
                .andExpect(jsonPath("$.accessories[1].name").value("充电器"))
                .andReturn().getResponse().getContentAsString();
        long assetId = Long.parseLong(created.replaceFirst("^\\{\\\"id\\\":(\\d+).*", "$1"));
        String qrToken = created.replaceFirst("(?s).*?\\\"qrToken\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(post("/api/assets/qr/{qrToken}/return", qrToken).with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/assets/qr/{qrToken}/return", qrToken).with(assetUser()).with(csrf())
                        .header("If-Match", assetRepository.findById(assetId).orElseThrow().getVersion()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedOut").value(false))
                .andExpect(jsonPath("$.assignedTo").doesNotExist())
                .andExpect(jsonPath("$.status.name").value("可领用"))
                .andExpect(jsonPath("$.updatedAt").exists());

        mockMvc.perform(delete("/api/assets/{id}", assetId).with(assetUser()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void requiresAssigneeWhenStatusMeansCheckedOut() throws Exception {
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long modelId = lookupRepository.findByTypeOrderByNameAsc(LookupType.MODEL).getFirst().getId();
        long categoryId = lookupRepository.findByTypeOrderByNameAsc(LookupType.CATEGORY).getFirst().getId();
        long statusId = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用").orElseThrow().getId();
        long locationId = lookupRepository.findByTypeOrderByNameAsc(LookupType.LOCATION).getFirst().getId();
        String body = """
                {"assetTag":"202600000077","name":"缺少领用人测试","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":false}
                """.formatted(companyId, modelId, categoryId, statusId, locationId);

        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("资产状态为“已经领出”时，必须填写领用人"));
    }

    @Test
    void bindsIndependentDisplayAssetToComputerAndExposesBothDirections() throws Exception {
        long companyId = lookupRepository.findByTypeOrderByNameAsc(LookupType.COMPANY).getFirst().getId();
        long modelId = lookupRepository.findByTypeOrderByNameAsc(LookupType.MODEL).getFirst().getId();
        long computerCategoryId = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.CATEGORY, "台式机")
                .orElseThrow().getId();
        long displayCategoryId = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.CATEGORY, "显示器")
                .orElseThrow().getId();
        long statusId = lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用")
                .orElseThrow().getId();
        long locationId = lookupRepository.findByTypeOrderByNameAsc(LookupType.LOCATION).getFirst().getId();

        String displayBody = """
                {"assetTag":"930000000001","name":"接口测试显示器","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"screenSize":"27英寸",
                 "displayResolution":"4K","displayInterface":"HDMI / DP","cpu":"不应保留","checkedOut":false}
                """.formatted(companyId, modelId, displayCategoryId, statusId, locationId);
        String displayJson = mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(displayBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.screenSize").value("27英寸"))
                .andExpect(jsonPath("$.displayResolution").value("4K"))
                .andExpect(jsonPath("$.cpu").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long displayId = Long.parseLong(displayJson.replaceFirst("^\\{\\\"id\\\":(\\d+).*", "$1"));

        String computerBody = """
                {"assetTag":"930000000002","name":"接口测试电脑","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"cpu":"i7-14700",
                 "screenSize":"不应保留","boundDisplayIds":[%d],"checkedOut":false}
                """.formatted(companyId, modelId, computerCategoryId, statusId, locationId, displayId);
        String computerJson = mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(computerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cpu").value("i7-14700"))
                .andExpect(jsonPath("$.screenSize").doesNotExist())
                .andExpect(jsonPath("$.boundDisplays[0].assetTag").value("930000000001"))
                .andReturn().getResponse().getContentAsString();
        long computerId = Long.parseLong(computerJson.replaceFirst("^\\{\\\"id\\\":(\\d+).*", "$1"));

        mockMvc.perform(get("/api/assets/{id}", displayId).with(assetUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boundComputer.id").value(computerId))
                .andExpect(jsonPath("$.boundComputer.assetTag").value("930000000002"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"relatedDevices", "accessories", "customParameters", "boundDisplayIds"})
    void rejectsNullCollectionEntriesWithoutWritingAssets(String field) throws Exception {
        var seed = assetRepository.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        long before = assetRepository.count();
        String body = """
                {"assetTag":"980000000001","name":"空元素测试","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"%s":[null]}
                """.formatted(seed.getCompany().getId(), seed.getModel().getId(), seed.getCategory().getId(),
                lookupRepository.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用").orElseThrow().getId(),
                seed.getLocation().getId(), field);
        mockMvc.perform(post("/api/assets").with(assetUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals(before, assetRepository.count());
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
        return user("admin").roles("SUPER_ADMIN").authorities(
                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),
                new SimpleGrantedAuthority("ASSET_VIEW"));
    }
}
