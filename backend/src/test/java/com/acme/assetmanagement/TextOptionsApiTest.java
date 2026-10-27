package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.UserRepository;
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
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:text-options;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class TextOptionsApiTest {
    @Autowired MockMvc mvc;
    @Autowired LookupRepository lookups;
    @Autowired AssetRepository assets;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    private AccountPrincipal admin() { return AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow()); }
    private String path(LookupType type) { return "/api/foundation/" + (type == LookupType.CPU ? "cpus" : "asset-names"); }
    private LookupValue option(LookupType type, String name) { return lookups.saveAndFlush(new LookupValue(type, name)); }
    private Asset device(String tag, String name, String cpu) {
        var seed = assets.findAll().getFirst(); var value = new Asset();
        value.setAssetTag(tag); value.setName(name); value.setCpu(cpu); value.setCompany(seed.getCompany()); value.setModel(seed.getModel());
        value.setCategory(lookups.findByTypeAndNameIgnoreCase(LookupType.CATEGORY, "台式机").orElseThrow());
        value.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用").orElseThrow()); value.setLocation(seed.getLocation());
        value.setDomainJoinStatus(DomainJoinStatus.JOINED); value.setNotes("历史备注");
        value.setRelatedDevices(List.of(new RelatedDevice("原鼠标", "原型号", "原SN", "原订单", "原规格", 1)));
        return assets.saveAndFlush(value);
    }
    private String snapshot() throws Exception {
        return mvc.perform(get("/api/assets").with(user(admin()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private void availability(LookupValue option, long version, boolean disabled, int expected) throws Exception {
        mvc.perform(put(path(option.getType()) + "/{id}/availability", option.getId()).with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", version, "disabled", disabled))))
                .andExpect(status().is(expected));
    }
    private Map<String, Object> body(Asset asset) {
        var values = new LinkedHashMap<String, Object>();
        values.put("assetTag", asset.getAssetTag()); values.put("name", asset.getName()); values.put("cpu", asset.getCpu());
        values.put("modelId", asset.getModel().getId()); values.put("companyId", asset.getCompany().getId());
        values.put("categoryId", asset.getCategory().getId()); values.put("statusId", asset.getStatus().getId()); values.put("locationId", asset.getLocation().getId());
        values.put("checkedOut", asset.isCheckedOut()); values.put("assignedTo", asset.getAssignedTo());
        values.put("domainJoinStatus", asset.getDomainJoinStatus()); values.put("relatedDevices", AssetResponse.from(asset).relatedDevices());
        values.put("notes", asset.getNotes()); return values;
    }

    @Test void readingAndAvailabilityOnlyAffectMetadataAndCountExistingTextWithoutRewritingIt() throws Exception {
        var cpu = option(LookupType.CPU, "Text-Test CPU"); var name = option(LookupType.ASSET_NAME, "Text-Test 电脑");
        var first = device("981000000001", name.getName(), cpu.getName());
        device("981000000002", name.getName().toLowerCase(Locale.ROOT), cpu.getName().toLowerCase(Locale.ROOT));
        String before = snapshot(); long version = first.getVersion();
        for (var value : List.of(cpu, name)) {
            mvc.perform(get(path(value.getType())).with(user(admin()))).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == " + value.getId() + ")].assetCount").value(org.hamcrest.Matchers.contains(2)));
            var metadata = (Object[]) em.createNativeQuery("select disabled, option_version from lookup_values where id = :id", Object[].class)
                    .setParameter("id", value.getId()).getSingleResult();
            assertNull(metadata[0]); assertNull(metadata[1]);
            availability(value, 0, true, 200);
            mvc.perform(get("/api/lookups").with(user(admin())).param("type", value.getType().name()))
                    .andExpect(jsonPath("$[?(@.id == " + value.getId() + ")]").isEmpty());
            assertEquals(before, snapshot()); assertEquals(version, first.getVersion());
            availability(value, 1, false, 200); assertEquals(before, snapshot());
        }
    }

    @Test void onlyAdministratorCanMaintainCpuAndAssetNameAndMutationsRequireCsrf() throws Exception {
        for (var type : List.of(LookupType.CPU, LookupType.ASSET_NAME)) {
            var value = option(type, "权限选项" + type.name());
            mvc.perform(get(path(type))).andExpect(status().isUnauthorized());
            mvc.perform(get(path(type)).with(user("editor").authorities(() -> "ASSET_VIEW", () -> "ASSET_EDIT"))).andExpect(status().isForbidden());
            mvc.perform(post(path(type)).with(user("editor").authorities(() -> "ASSET_EDIT")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"无权限\"}")).andExpect(status().isForbidden());
            mvc.perform(put(path(type) + "/{id}/availability", value.getId()).with(user("editor").authorities(() -> "ASSET_EDIT")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"disabled\":true}")).andExpect(status().isForbidden());
            mvc.perform(put(path(type) + "/{id}/availability", value.getId()).with(user(admin()))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"disabled\":true}")).andExpect(status().isForbidden());
            assertFalse(value.isDisabled());
        }
    }

    @Test void createRespectsFieldLengthsDuplicatesVersionsAndDictionaryTypeBoundaries() throws Exception {
        String before = snapshot();
        for (var type : List.of(LookupType.CPU, LookupType.ASSET_NAME)) {
            String name = "New-" + type.name();
            mvc.perform(post(path(type)).with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", " " + name + " ")))).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(name));
            var value = lookups.findByTypeAndNameIgnoreCase(type, name).orElseThrow();
            availability(value, 0, true, 200); availability(value, 0, false, 409);
            mvc.perform(post(path(type)).with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", name.toLowerCase(Locale.ROOT))))).andExpect(status().isConflict());
            for (String invalid : List.of(" ", "x".repeat(type == LookupType.CPU ? 201 : 161)))
                mvc.perform(post(path(type)).with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", invalid)))).andExpect(status().isBadRequest());
            mvc.perform(put(path(type) + "/{id}/availability", value.getId()).with(user(admin())).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":false}")).andExpect(status().isBadRequest());
            String other = path(type == LookupType.CPU ? LookupType.ASSET_NAME : LookupType.CPU);
            mvc.perform(put(other + "/{id}/availability", value.getId()).with(user(admin())).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"disabled\":false}")).andExpect(status().isNotFound());
        }
        assertEquals(before, snapshot());
    }

    @Test void disabledTextBlocksNewAssignmentsAndClonesButExistingValuesRemainEditable() throws Exception {
        var cpu = option(LookupType.CPU, "Disabled-Test CPU"); var name = option(LookupType.ASSET_NAME, "Disabled-Test 电脑");
        var original = device("981000000003", name.getName(), cpu.getName());
        for (var value : List.of(cpu, name)) {
            availability(value, 0, true, 200);
            var create = body(original); create.put("assetTag", "981000000099"); String before = snapshot(); long count = assets.count();
            mvc.perform(post("/api/assets").with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
                    .andExpect(status().isConflict());
            mvc.perform(post("/api/assets").param("cloneSourceId", original.getId().toString()).with(user(admin())).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create))).andExpect(status().isConflict());
            var other = device("981000000004", "其他电脑", "其他CPU"); var change = body(other);
            change.put(value.getType() == LookupType.CPU ? "cpu" : "name", value.getName());
            mvc.perform(put("/api/assets/{id}", other.getId()).with(user(admin())).with(csrf()).header("If-Match", other.getVersion())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(change))).andExpect(status().isConflict());
            assets.delete(other); assets.flush(); assertEquals(count, assets.count()); assertEquals(before, snapshot());
            var edit = body(original); edit.put("notes", "只改备注" + value.getType().name());
            mvc.perform(put("/api/assets/{id}", original.getId()).with(user(admin())).with(csrf()).header("If-Match", original.getVersion())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(name.getName())).andExpect(jsonPath("$.cpu").value(cpu.getName()))
                    .andExpect(jsonPath("$.relatedDevices[0].serialNumber").value("原SN")).andExpect(jsonPath("$.domainJoinStatus").value("JOINED"));
        }
    }
}
