package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogRepository;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.setting.DomainStatusPreferenceRepository;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.UserAccount;
import com.acme.assetmanagement.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "app.asset-images.directory=${java.io.tmpdir}/asset-management-test-images"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DomainJoinStatusApiTest {
    @Autowired MockMvc mvc;
    @Autowired AssetRepository assets;
    @Autowired LookupRepository lookups;
    @Autowired UserRepository users;
    @Autowired AuditLogRepository audit;
    @Autowired DomainStatusPreferenceRepository preferences;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;

    @Test
    void legacyReadUsesUnknownWithoutBackfillingAndNonComputersHaveNoDomainState() throws Exception {
        var asset = computer();
        long version = asset.getVersion();
        mvc.perform(get("/api/assets/{id}", asset.getId()).with(operator()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.domainJoinStatus").value("UNKNOWN"));
        em.flush(); em.clear();
        var stored = assets.findById(asset.getId()).orElseThrow();
        assertNull(stored.getDomainJoinStatus()); assertEquals(version, stored.getVersion());
        stored.setCategory(lookups.findByTypeAndNameIgnoreCase(LookupType.CATEGORY, "显示器").orElseThrow());
        assets.saveAndFlush(stored);
        mvc.perform(get("/api/assets/{id}", stored.getId()).with(operator()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.domainJoinStatus").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOINED", "NOT_JOINED", "UNKNOWN"})
    void savesDomainStateWithAuditWithoutChangingCustodyValuationOrPhotos(String state) throws Exception {
        var asset = computer();
        asset.setCheckedOut(true); asset.setAssignedTo("原领用人"); asset.setAssignedUserId(null);
        asset.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用").orElseThrow());
        asset.setPurchasePrice(new BigDecimal("4000.00")); asset.setCurrentValue(new BigDecimal("2500.00"));
        asset.setImageUrls(List.of("https://assets.example.test/computer.png"));
        asset.setImageUrl("https://assets.example.test/computer.png"); assets.saveAndFlush(asset);
        var body = editBody(asset); body.put("domainJoinStatus", state);
        mvc.perform(put("/api/assets/{id}", asset.getId()).with(operator()).with(csrf())
                        .header("If-Match", asset.getVersion()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.domainJoinStatus").value(state))
                .andExpect(jsonPath("$.assignedTo").value("原领用人")).andExpect(jsonPath("$.checkedOut").value(true))
                .andExpect(jsonPath("$.purchasePrice").value(4000)).andExpect(jsonPath("$.currentValue").value(2500))
                .andExpect(jsonPath("$.imageUrls[0]").value("https://assets.example.test/computer.png"));
        assertEquals(DomainJoinStatus.valueOf(state), assets.findById(asset.getId()).orElseThrow().getDomainJoinStatus());
        if (!state.equals("UNKNOWN")) assertTrue(audit.findAll().stream().anyMatch(log ->
                log.getAction() == AuditAction.ASSET_UPDATE && log.getChangesJson().contains("入域状态")
                        && log.getChangesJson().contains(DomainJoinStatus.valueOf(state).label())));
    }

    @Test
    void oldClientsDoNotClearDomainStateAndStaleEditsAreRejected() throws Exception {
        var asset = computer(); asset.setDomainJoinStatus(DomainJoinStatus.JOINED); assets.saveAndFlush(asset);
        long oldVersion = asset.getVersion();
        var body = editBody(asset); body.put("notes", "旧客户端补充备注");
        mvc.perform(put("/api/assets/{id}", asset.getId()).with(operator()).with(csrf()).header("If-Match", oldVersion)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.domainJoinStatus").value("JOINED"));
        body.put("domainJoinStatus", "NOT_JOINED");
        mvc.perform(put("/api/assets/{id}", asset.getId()).with(operator()).with(csrf()).header("If-Match", oldVersion)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isConflict());
        assertEquals(DomainJoinStatus.JOINED, asset.getDomainJoinStatus());
    }

    @Test
    void clonesCannotInheritVerifiedDomainStateEvenWithForgedPayload() throws Exception {
        var source = computer(); source.setDomainJoinStatus(DomainJoinStatus.JOINED); assets.saveAndFlush(source);
        var body = editBody(source); body.put("assetTag", "980000001001"); body.put("manufacturerSerialNumber", null);
        body.put("domainJoinStatus", "JOINED");
        mvc.perform(post("/api/assets").param("cloneSourceId", source.getId().toString()).with(operator()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.domainJoinStatus").value("UNKNOWN"));
        assertEquals(DomainJoinStatus.JOINED, source.getDomainJoinStatus());
    }

    @Test
    void domainChangesRequireEditPermissionAndValidComputerState() throws Exception {
        var asset = computer(); var body = editBody(asset); body.put("domainJoinStatus", "JOINED");
        mvc.perform(put("/api/assets/{id}", asset.getId()).with(user("employee").authorities(() -> "ASSET_VIEW"))
                        .with(csrf()).header("If-Match", asset.getVersion()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        body.put("domainJoinStatus", "ANYTHING");
        mvc.perform(put("/api/assets/{id}", asset.getId()).with(operator()).with(csrf()).header("If-Match", asset.getVersion())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.put("domainJoinStatus", "JOINED");
        body.put("categoryId", lookups.findByTypeAndNameIgnoreCase(LookupType.CATEGORY, "显示器").orElseThrow().getId());
        mvc.perform(put("/api/assets/{id}", asset.getId()).with(operator()).with(csrf()).header("If-Match", asset.getVersion())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
    }

    @Test
    void returnPreservesManualDomainVerification() throws Exception {
        var asset = computer(); asset.setDomainJoinStatus(DomainJoinStatus.JOINED);
        asset.setCheckedOut(true); asset.setAssignedTo("员工"); assets.saveAndFlush(asset);
        mvc.perform(post("/api/assets/{id}/return", asset.getId()).with(operator()).with(csrf()).header("If-Match", asset.getVersion()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.domainJoinStatus").value("JOINED"))
                .andExpect(jsonPath("$.checkedOut").value(false));
    }

    @Test
    void foundationOptionsAreFixedAndOnlyAdminsCanChangeDescriptionsWithVersionControl() throws Exception {
        var asset = computer(); long version = asset.getVersion();
        mvc.perform(get("/api/foundation/domain-statuses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/foundation/domain-statuses").with(operator()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("JOINED")).andExpect(jsonPath("$[0].label").value("已入域"));
        assertEquals(0, preferences.count());
        String request = "{\"description\":\"运维核验已入域\",\"sortOrder\":12,\"version\":null}";
        mvc.perform(put("/api/foundation/domain-statuses/JOINED").with(operator()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mvc.perform(put("/api/foundation/domain-statuses/JOINED").with(user("admin").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mvc.perform(put("/api/foundation/domain-statuses/JOINED").with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("已入域")).andExpect(jsonPath("$.description").value("运维核验已入域"))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(put("/api/foundation/domain-statuses/JOINED").with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isConflict());
        mvc.perform(put("/api/foundation/domain-statuses/JOINED").with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"核验记录\",\"sortOrder\":40,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/foundation/domain-statuses").with(operator())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("NOT_JOINED")).andExpect(jsonPath("$[2].description").value("核验记录"));
        assertEquals(version, asset.getVersion()); assertNull(asset.getDomainJoinStatus());
        assertTrue(audit.findAll().stream().anyMatch(log -> "DOMAIN_STATUS".equals(log.getTargetType())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"description\":\"说明\",\"sortOrder\":-1}", "{\"description\":\"说明\",\"sortOrder\":1000}", "{\"sortOrder\":10}"})
    void rejectsInvalidFoundationMetadata(String body) throws Exception {
        mvc.perform(put("/api/foundation/domain-statuses/JOINED").with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertEquals(0, preferences.count());
    }

    @Test
    void rejectsInventedFoundationCodes() throws Exception {
        mvc.perform(put("/api/foundation/domain-statuses/OTHER").with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"说明\",\"sortOrder\":10}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "已入域"})
    void csvRemainsCompatibleAndExportsDomainAlongsideExistingValuation(String state) throws Exception {
        String header = "资产编号*,资产名称*,所属公司*,设备型号*,资产分类*,资产状态*,存放位置*,采购价格(元),当前价值(元)";
        String row = "980000001002,导入测试电脑,域状态测试公司,测试电脑型号,台式机,当前可用,仓库,3000,2400";
        String csv = header + (state.isEmpty() ? "" : ",入域状态") + "\n" + row + (state.isEmpty() ? "" : "," + state) + "\n";
        var file = new MockMultipartFile("file", "domain.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/assets/import/preview").file(file).with(operator()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canImport").value(true));
        mvc.perform(multipart("/api/assets/import/commit").file(file).with(operator()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.importedCount").value(1));
        var asset = assets.findByAssetTagIgnoreCase("980000001002").orElseThrow();
        assertEquals(state.isEmpty() ? null : DomainJoinStatus.JOINED, asset.getDomainJoinStatus());
        assertEquals(0, new BigDecimal("2400").compareTo(asset.getCurrentValue()));
        em.flush(); em.clear();
        String exported = mvc.perform(get("/api/assets/export.csv").with(operator())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(exported.contains("当前价值(元)")); assertTrue(exported.contains("入域状态")); assertTrue(exported.contains("2400.00"));
        if (!state.isEmpty()) assertTrue(exported.contains("已入域"));
    }

    @Test
    void invalidDomainCsvFailsPreviewBeforeAnyImport() throws Exception {
        String csv = "资产编号*,资产名称*,所属公司*,设备型号*,资产分类*,资产状态*,存放位置*,入域状态\n"
                + "980000001003,电脑,公司,型号,台式机,当前可用,仓库,随便填写\n";
        var file = new MockMultipartFile("file", "bad.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/assets/import/preview").file(file).with(operator()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canImport").value(false));
        assertFalse(assets.existsByAssetTagIgnoreCase("980000001003"));
    }

    private Asset computer() {
        var asset = assets.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        asset.setCategory(lookups.findByTypeAndNameIgnoreCase(LookupType.CATEGORY, "台式机").orElseThrow());
        asset.setDomainJoinStatus(null);
        return assets.saveAndFlush(asset);
    }

    private Map<String, Object> editBody(Asset asset) {
        var response = AssetResponse.from(asset);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assetTag", asset.getAssetTag()); body.put("name", asset.getName()); body.put("ownershipDepartment", asset.getOwnershipDepartment());
        body.put("companyId", asset.getCompany().getId()); body.put("modelId", asset.getModel().getId());
        body.put("categoryId", asset.getCategory().getId()); body.put("statusId", asset.getStatus().getId()); body.put("locationId", asset.getLocation().getId());
        body.put("cpu", asset.getCpu()); body.put("memory", asset.getMemory()); body.put("storage", asset.getStorage()); body.put("graphicsCard", asset.getGraphicsCard());
        body.put("manufacturerSerialNumber", asset.getManufacturerSerialNumber()); body.put("orderNumber", asset.getOrderNumber());
        body.put("purchasePrice", asset.getPurchasePrice()); body.put("currentValue", asset.getCurrentValue());
        body.put("checkedOut", asset.isCheckedOut()); body.put("assignedTo", asset.getAssignedTo()); body.put("assignedUserId", asset.getAssignedUserId());
        body.put("imageUrls", response.imageUrls()); body.put("notes", asset.getNotes()); body.put("boundDisplayIds", response.boundDisplays().stream().map(AssetResponse.AssetLinkResponse::id).toList());
        body.put("boundComputerId", response.boundComputer() == null ? null : response.boundComputer().id());
        body.put("relatedDevices", response.relatedDevices()); body.put("accessories", response.accessories()); body.put("customParameters", response.customParameters());
        return body;
    }

    private RequestPostProcessor operator() {
        var account = users.findByUsernameIgnoreCase("domain-tester").orElseGet(() -> {
            var value = new UserAccount(); value.setUsername("domain-tester"); value.setDisplayName("入域测试员"); value.setPasswordHash("test-hash");
            return users.saveAndFlush(value);
        });
        return user(AccountPrincipal.from(account));
    }
}
