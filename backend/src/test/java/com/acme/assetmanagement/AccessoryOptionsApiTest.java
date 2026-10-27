package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.lookup.AccessoryOptionRepository;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:accessory-options;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class AccessoryOptionsApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Autowired AccessoryOptionRepository options;
    @Autowired AuditLogRepository audit;
    private AccountPrincipal admin() { return AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow()); }
    private JsonNode create(String name, String model, String specification) throws Exception {
        return json.readTree(mvc.perform(post("/api/foundation/accessories").with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name, "model", model, "specification", specification))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private String snapshot() throws Exception {
        return mvc.perform(get("/api/assets").with(user(admin()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private JsonNode availability(JsonNode option, boolean disabled, int expected) throws Exception {
        String response = mvc.perform(put("/api/foundation/accessories/{id}/availability", option.get("id").asLong()).with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", option.get("version").asLong(), "disabled", disabled))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    @Test void catalogueEditsAndAvailabilityNeverTouchHistoricalAccessoriesOrAssetVersions() throws Exception {
        var asset = assets.findAll().getFirst();
        asset.setRelatedDevices(List.of(new RelatedDevice("旧鼠标", "旧型号", "原SN", "原订单", "原规格", 2)));
        asset.setAccessories(List.of(new AssetAccessory("历史充电器", "旧接口", 1))); assets.saveAndFlush(asset);
        String before = snapshot(); long version = asset.getVersion(), count = assets.count();
        mvc.perform(get("/api/foundation/accessories").with(user(admin()))).andExpect(status().isOk());
        assertEquals(0, options.count(), "Reading must not derive or backfill the catalogue from historical assets");
        var option = create(" 鼠标 ", " M185 ", " 无线 ");
        assertEquals("鼠标", option.get("name").asText());
        String update = mvc.perform(put("/api/foundation/accessories/{id}", option.get("id").asLong()).with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"键盘\",\"model\":\"K120\",\"specification\":\"USB\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andReturn().getResponse().getContentAsString();
        var changed = json.readTree(update); var disabled = availability(changed, true, 200);
        mvc.perform(get("/api/foundation/accessories/choices").with(user(admin()))).andExpect(status().isOk()).andExpect(jsonPath("$[0]").doesNotExist());
        mvc.perform(get("/api/foundation/accessories").with(user(admin()))).andExpect(jsonPath("$[0].disabled").value(true));
        availability(disabled, false, 200);
        assertEquals(before, snapshot()); assertEquals(version, asset.getVersion()); assertEquals(count, assets.count());
        assertTrue(audit.findAll().stream().anyMatch(log -> "ACCESSORY_OPTION".equals(log.getTargetType())));
    }

    @Test void permissionsSeparateSelectableChoicesFromAdministratorMaintenanceAndRequireCsrf() throws Exception {
        var option = create("权限鼠标", "", "");
        mvc.perform(get("/api/foundation/accessories/choices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/foundation/accessories/choices").with(user("viewer").authorities(() -> "ASSET_VIEW"))).andExpect(status().isForbidden());
        for (String permission : List.of("ASSET_EDIT", "ASSET_CREATE")) {
            mvc.perform(get("/api/foundation/accessories/choices").with(user("editor").authorities(() -> permission)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("权限鼠标"));
            mvc.perform(get("/api/foundation/accessories").with(user("editor").authorities(() -> permission))).andExpect(status().isForbidden());
            mvc.perform(post("/api/foundation/accessories").with(user("editor").authorities(() -> permission)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"无权新增\"}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/foundation/accessories/{id}", option.get("id").asLong()).with(user("editor").authorities(() -> permission)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"无权修改\",\"version\":0}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/foundation/accessories/{id}/availability", option.get("id").asLong()).with(user("editor").authorities(() -> permission)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"disabled\":true}")).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/foundation/accessories").with(user(admin())).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"缺CSRF\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/foundation/accessories/{id}/availability", option.get("id").asLong()).with(user(admin()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"disabled\":true}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/foundation/accessories/{id}", option.get("id").asLong()).with(user(admin())).with(csrf())).andExpect(status().isMethodNotAllowed());
        assertEquals(1, options.count());
    }

    @Test void staleVersionsCannotOverwriteEditsOrAvailabilityAndUnknownIdsAreRejected() throws Exception {
        var original = create("版本鼠标", "A", "USB");
        var disabled = availability(original, true, 200); assertEquals(1, disabled.get("version").asLong());
        availability(original, false, 409);
        mvc.perform(put("/api/foundation/accessories/{id}", original.get("id").asLong()).with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"过期覆盖\"}")).andExpect(status().isConflict());
        mvc.perform(put("/api/foundation/accessories/{id}", original.get("id").asLong()).with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"无版本\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/foundation/accessories/999999").with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"不存在\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/foundation/accessories").with(user(admin()))).andExpect(jsonPath("$[0].name").value("版本鼠标"))
                .andExpect(jsonPath("$[0].disabled").value(true));
    }

    @Test void validateCatalogueFieldsAndDuplicatesButAllowTheSameNameWithDifferentModels() throws Exception {
        var mouse = create("Mouse", "M185", "USB"); availability(mouse, true, 200);
        mvc.perform(post("/api/foundation/accessories").with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" mouse \",\"model\":\"m185\",\"specification\":\"usb\"}")).andExpect(status().isConflict());
        var other = create("Mouse", "M186", "USB");
        mvc.perform(put("/api/foundation/accessories/{id}", other.get("id").asLong()).with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"Mouse\",\"model\":\"M185\",\"specification\":\"USB\"}"))
                .andExpect(status().isConflict());
        for (var body : List.of(Map.of("name", " "), Map.of("name", "x".repeat(121)), Map.of("name", "正常", "model", "x".repeat(161)),
                Map.of("name", "正常", "specification", "x".repeat(301))))
            mvc.perform(post("/api/foundation/accessories").with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        var minimal = create("最少字段", "", ""); assertEquals("", minimal.get("model").asText());
        mvc.perform(get("/api/foundation/accessories/choices").with(user(admin())))
                .andExpect(jsonPath("$[?(@.id == " + mouse.get("id").asLong() + ")]").isEmpty());
    }
}
