package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:model-options;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ModelOptionsApiTest {
    @Autowired MockMvc mvc;
    @Autowired LookupRepository lookups;
    @Autowired AssetRepository assets;
    @Autowired UserRepository users;
    @Autowired AuditLogRepository audit;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    private AccountPrincipal admin() { return AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow()); }
    private LookupValue model() { return lookups.saveAndFlush(new LookupValue(LookupType.MODEL, "ThinkPad-型号维护测试")); }
    private Asset device(LookupValue model, String tag) {
        var seed = assets.findAll().getFirst(); var device = new Asset();
        device.setAssetTag(tag); device.setName("历史电脑"); device.setModel(model); device.setCompany(seed.getCompany());
        device.setCategory(lookups.findByTypeAndNameIgnoreCase(LookupType.CATEGORY, "台式机").orElseThrow());
        device.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用").orElseThrow()); device.setLocation(seed.getLocation());
        device.setAssignedTo("历史领用人"); device.setCheckedOut(true); device.setCpu("历史CPU"); device.setMemory("16G");
        device.setStorage("512G"); device.setNotes("历史备注"); device.setDomainJoinStatus(DomainJoinStatus.JOINED);
        device.setPurchasePrice(new BigDecimal("4567.00")); device.setCurrentValue(new BigDecimal("2345.00"));
        device.setImageUrls(List.of("https://assets.example.test/historical.png")); device.setImageUrl("https://assets.example.test/historical.png");
        return assets.saveAndFlush(device);
    }
    private String snapshot() throws Exception {
        return mvc.perform(get("/api/assets").with(user(admin()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private void availability(LookupValue model, long version, boolean disabled, int expected) throws Exception {
        mvc.perform(put("/api/foundation/models/{id}/availability", model.getId()).with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", version, "disabled", disabled))))
                .andExpect(status().is(expected));
    }
    private Map<String, Object> body(Asset device) {
        var response = AssetResponse.from(device); var body = new LinkedHashMap<String, Object>();
        body.put("assetTag", device.getAssetTag()); body.put("name", device.getName()); body.put("modelId", device.getModel().getId());
        body.put("companyId", device.getCompany().getId()); body.put("categoryId", device.getCategory().getId());
        body.put("statusId", device.getStatus().getId()); body.put("locationId", device.getLocation().getId());
        body.put("checkedOut", device.isCheckedOut()); body.put("assignedTo", device.getAssignedTo()); body.put("assignedUserId", device.getAssignedUserId());
        body.put("purchasePrice", device.getPurchasePrice()); body.put("currentValue", device.getCurrentValue());
        body.put("cpu", device.getCpu()); body.put("memory", device.getMemory()); body.put("storage", device.getStorage());
        body.put("notes", device.getNotes()); body.put("domainJoinStatus", device.getDomainJoinStatus()); body.put("imageUrls", response.imageUrls());
        body.put("relatedDevices", List.of()); body.put("accessories", List.of()); body.put("customParameters", List.of());
        body.put("boundDisplayIds", List.of()); return body;
    }

    @Test void readDefaultsDoNotBackfillAndAvailabilityNeverChangesExistingAssetResponsesOrVersions() throws Exception {
        var model = model(); var first = device(model, "980000009001"); var second = device(model, "980000009002");
        String before = snapshot(); long firstVersion = first.getVersion(), secondVersion = second.getVersion();
        mvc.perform(get("/api/foundation/models").with(user(admin()))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + model.getId() + ")].assetCount").value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$[?(@.id == " + model.getId() + ")].disabled").value(org.hamcrest.Matchers.contains(false)));
        var metadata = (Object[]) em.createNativeQuery("select disabled, option_version from lookup_values where id = :id", Object[].class)
                .setParameter("id", model.getId()).getSingleResult();
        assertNull(metadata[0]); assertNull(metadata[1]);
        availability(model, 0, true, 200);
        assertEquals(before, snapshot()); assertEquals(firstVersion, first.getVersion()); assertEquals(secondVersion, second.getVersion());
        mvc.perform(get("/api/lookups").param("type", "MODEL").with(user(admin()))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + model.getId() + ")]").isEmpty());
        availability(model, 1, false, 200); assertEquals(before, snapshot());
        mvc.perform(get("/api/lookups").param("type", "MODEL").with(user(admin())))
                .andExpect(jsonPath("$[?(@.id == " + model.getId() + ")].name").value(org.hamcrest.Matchers.contains(model.getName())));
        assertTrue(audit.findAll().stream().anyMatch(log -> log.getAction() == AuditAction.SETTING_UPDATE && "LOOKUP".equals(log.getTargetType())));
    }

    @Test void foundationRequiresSuperAdminAndCsrfEvenForAssetEditors() throws Exception {
        var model = model();
        mvc.perform(get("/api/foundation/models")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/foundation/models").with(user("editor").authorities(() -> "ASSET_VIEW", () -> "ASSET_EDIT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/foundation/models").with(user("editor").authorities(() -> "ASSET_CREATE")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"无权新增\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/foundation/models/{id}/availability", model.getId()).with(user(admin()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"disabled\":true}")).andExpect(status().isForbidden());
        assertFalse(model.isDisabled());
    }

    @Test void metadataUsesReviewedVersionAndCannotChangeOtherDictionaryTypes() throws Exception {
        var model = model(); availability(model, 0, true, 200); availability(model, 0, false, 409);
        assertTrue(model.isDisabled()); assertEquals(1, model.getOptionVersion());
        availability(model, 1, false, 200); assertEquals(2, model.getOptionVersion());
        var cpu = lookups.saveAndFlush(new LookupValue(LookupType.CPU, "受保护CPU")); availability(cpu, 0, true, 404);
        mvc.perform(put("/api/foundation/models/{id}/availability", model.getId()).with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":true}")).andExpect(status().isBadRequest());
        assertEquals("ThinkPad-型号维护测试", model.getName()); assertFalse(cpu.isDisabled());
    }

    @Test void createOnlyAddsAnOptionAndRejectsBlankOversizedOrDisabledDuplicates() throws Exception {
        String before = snapshot(); long count = lookups.count();
        mvc.perform(post("/api/foundation/models").with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  New-Model  \"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("New-Model"));
        assertEquals(count + 1, lookups.count()); assertEquals(before, snapshot());
        var model = lookups.findByTypeAndNameIgnoreCase(LookupType.MODEL, "New-Model").orElseThrow(); availability(model, 0, true, 200);
        mvc.perform(post("/api/foundation/models").with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-model\"}")).andExpect(status().isConflict());
        for (String name : List.of(" ", "x".repeat(121))) mvc.perform(post("/api/foundation/models").with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name)))).andExpect(status().isBadRequest());
        assertEquals(before, snapshot()); assertTrue(model.isDisabled());
    }

    @Test void disabledModelCannotBeNewlyAssignedByIdNameOrCloneAndUsedModelCannotBeDeleted() throws Exception {
        var model = model(); var original = device(model, "980000009003"); availability(model, 0, true, 200);
        long count = assets.count(); String before = snapshot(); var body = body(original); body.put("assetTag", "980000009099");
        for (boolean byName : List.of(false, true)) {
            body.put("modelId", byName ? null : model.getId()); body.put("modelName", byName ? model.getName() : null);
            mvc.perform(post("/api/assets").with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body))).andExpect(status().isConflict());
        }
        mvc.perform(post("/api/assets").param("cloneSourceId", original.getId().toString()).with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isConflict());
        mvc.perform(delete("/api/lookups/{id}", model.getId()).with(user(admin())).with(csrf())).andExpect(status().isConflict());
        assertEquals(count, assets.count()); assertEquals(before, snapshot());
    }

    @Test void existingAssetKeepsItsDisabledModelWhenEditingOtherFieldsByIdOrName() throws Exception {
        var model = model(); var original = device(model, "980000009004"); availability(model, 0, true, 200);
        for (boolean byName : List.of(false, true)) {
            var body = body(original); body.put("notes", byName ? "更新备注二" : "更新备注一");
            body.put("modelId", byName ? null : model.getId()); body.put("modelName", byName ? model.getName() : null);
            mvc.perform(put("/api/assets/{id}", original.getId()).with(user(admin())).with(csrf()).header("If-Match", original.getVersion())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.model.name").value(model.getName())).andExpect(jsonPath("$.assignedTo").value("历史领用人"))
                    .andExpect(jsonPath("$.domainJoinStatus").value("JOINED")).andExpect(jsonPath("$.purchasePrice").value(4567));
        }
        assertTrue(model.isDisabled()); assertEquals(model.getId(), original.getModel().getId());
    }
}
