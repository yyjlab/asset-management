package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:registrationdb;DB_CLOSE_DELAY=-1", "app.workflow-scheduling-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class RegistrationApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Autowired ObjectMapper json;
    UserAccount admin() { return users.findByUsernameIgnoreCase("admin").orElseThrow(); }
    UserAccount employee(String name) {
        var u = new UserAccount(); u.setUsername(name); u.setDisplayName("同名员工"); u.setPasswordHash("unused-test-password-hash");
        u.setAccessConfigured(true); u.setPermissions(Set.of()); u.setDataScope(DataScope.SELF);
        return users.saveAndFlush(u);
    }
    Map<String, Object> body(String serial) {
        var b = new LinkedHashMap<String, Object>(); b.put("requestKey", UUID.randomUUID().toString());
        b.put("name", "员工笔记本"); b.put("model", "登记测试型号"); b.put("serialNumber", serial);
        b.put("department", "测试部"); b.put("cpu", "I7"); b.put("memory", "32GB"); b.put("storage", "1TB"); b.put("notes", "待审核");
        return b;
    }
    JsonNode submit(UserAccount owner, Map<String, Object> b) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/registrations").file(data(b))
                .with(user(AccountPrincipal.from(owner))).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    MockMultipartFile data(Map<String, Object> b) { return new MockMultipartFile("data", "data.json", "application/json", json.writeValueAsBytes(b)); }
    JsonNode action(UserAccount actor, JsonNode detail, String action, Map<String, Object> values) throws Exception {
        var b = new LinkedHashMap<>(values); b.put("version", detail.path("request").path("version").asLong());
        return json.readTree(mvc.perform(post("/api/registrations/{id}/" + action, detail.path("request").path("id").asLong())
                .with(user(AccountPrincipal.from(actor))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    String snapshot() throws Exception { return mvc.perform(get("/api/assets").with(user(AccountPrincipal.from(admin())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); }

    @Test void employeeCanSubmitWithoutAssetCreationPermissionAndNoOldDataChanges() throws Exception {
        var owner = employee("registration-owner"); var other = employee("registration-outsider"); String before = snapshot();
        var b = body("REG-OWNER-1"); b.put("ownerId", other.getId()); var result = submit(owner, b);
        assertEquals(owner.getId(), result.path("request").path("ownerId").asLong()); assertEquals("SUBMITTED", result.path("request").path("state").asString());
        assertEquals(before, snapshot());
        mvc.perform(get("/api/registrations").with(user(AccountPrincipal.from(owner)))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/registrations").with(user(AccountPrincipal.from(other)))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/registrations/{id}", result.path("request").path("id").asLong()).with(user(AccountPrincipal.from(other)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/registrations").param("review", "true").with(user(AccountPrincipal.from(owner)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/registrations").with(user(AccountPrincipal.from(admin())))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/registrations").param("review", "true").with(user(AccountPrincipal.from(admin())))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }
    @Test void submissionIsIdempotentAndDuplicateOpenSerialIsRejected() throws Exception {
        var owner = employee("registration-duplicate"); var b = body("Reg-Duplicate"); var original = submit(owner, b);
        b.put("name", "重试不覆盖原记录"); var repeated = submit(owner, b);
        assertEquals(original.path("request").path("id"), repeated.path("request").path("id")); assertEquals("员工笔记本", repeated.path("request").path("name").asString());
        mvc.perform(multipart("/api/registrations").file(data(body("  reg-duplicate  "))).with(user(AccountPrincipal.from(owner))).with(csrf())).andExpect(status().isConflict());
        mvc.perform(multipart("/api/registrations").file(data(b)).with(user(AccountPrincipal.from(employee("registration-key-outsider")))).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void loginCsrfAndEvidenceAreIndependentlyRequired() throws Exception {
        mvc.perform(get("/api/registrations")).andExpect(status().isUnauthorized());
        var owner = employee("registration-validation"); var b = body("");
        mvc.perform(multipart("/api/registrations").file(data(b)).with(user(AccountPrincipal.from(owner)))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/registrations").file(data(b)).with(user(AccountPrincipal.from(owner))).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/registrations").file(data(b)).file(new MockMultipartFile("photos", "fake.png", "image/png", "not an image".getBytes()))
                .with(user(AccountPrincipal.from(owner))).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/registrations").param("state", "invalid").with(user(AccountPrincipal.from(owner)))).andExpect(status().isBadRequest());
    }
    @Test void privatePhotosAreScopedAndSupplementsKeepHistory() throws Exception {
        var owner = employee("registration-photo"); var other = employee("registration-photo-other"); var b = body("");
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfEsAAAAASUVORK5CYII=");
        var result = json.readTree(mvc.perform(multipart("/api/registrations").file(data(b)).file(new MockMultipartFile("photos", "photo.png", "image/png", png))
                .with(user(AccountPrincipal.from(owner))).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String url = result.path("photos").get(0).asString();
        mvc.perform(get(url).with(user(AccountPrincipal.from(owner)))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(content().bytes(png));
        mvc.perform(get(url).with(user(AccountPrincipal.from(other)))).andExpect(status().isNotFound());
        var review = action(admin(), result, "review", Map.of("decision", "NEEDS_INFO", "note", "请补充SN"));
        b.put("version", review.path("request").path("version").asLong()); b.put("serialNumber", "PHOTO-SUPPLEMENT-1");
        var updated = json.readTree(mvc.perform(multipart("/api/registrations/{id}/supplement", result.path("request").path("id").asLong()).file(data(b))
                .with(user(AccountPrincipal.from(owner))).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("SUBMITTED", updated.path("request").path("state").asString()); assertEquals(url, updated.path("photos").get(0).asString()); assertEquals(3, updated.path("events").size());
        var withdrawn = action(owner, updated, "withdraw", Map.of("note", "资料需要重新核对"));
        mvc.perform(post("/api/registrations/{id}/review", withdrawn.path("request").path("id").asLong()).with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", withdrawn.path("request").path("version").asLong(), "decision", "REJECTED", "note", "不能修改终态")))).andExpect(status().isConflict());
    }
    @Test void approveNewCreatesExactlyOneExplicitlyAssignedAssetAndPreservesOldAssets() throws Exception {
        var owner = employee("registration-approved"); var result = submit(owner, body("REG-APPROVE-NEW"));
        String before = snapshot(); long count = assets.count(); var seed = assets.findAll().getFirst();
        var fields = Map.<String, Object>of("companyId", seed.getCompany().getId(), "categoryId", seed.getCategory().getId(), "locationId", seed.getLocation().getId(), "confirmed", true, "note", "已核对实物");
        var approved = action(admin(), result, "approve-create", fields);
        assertEquals("APPROVED", approved.path("request").path("state").asString()); assertEquals(count + 1, assets.count());
        var newAsset = assets.findById(approved.path("request").path("approvedAssetId").asLong()).orElseThrow();
        assertEquals(owner.getId(), newAsset.getAssignedUserId()); assertEquals("REG-APPROVE-NEW", newAsset.getManufacturerSerialNumber()); assertTrue(newAsset.isCheckedOut());
        var after = json.readTree(snapshot()); var old = json.readTree(before); var oldIds = new HashSet<Long>(); old.forEach(a -> oldIds.add(a.path("id").asLong()));
        assertEquals(old.size(), after.size() - 1); for (var a : old) { var match = java.util.stream.StreamSupport.stream(after.spliterator(), false).filter(value -> value.path("id").equals(a.path("id"))).findFirst().orElseThrow(); assertEquals(a, match); }
        mvc.perform(post("/api/registrations/{id}/approve-create", approved.path("request").path("id").asLong()).with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new LinkedHashMap<String, Object>(fields) {{ put("version", approved.path("request").path("version").asLong()); }}))).andExpect(status().isConflict());
        assertEquals(count + 1, assets.count());
    }
    @Test void existingAssetLinkNeverChangesLedgerAndRequiresMatchingOwnerSerialAndVersion() throws Exception {
        var owner = employee("registration-existing"); var a = assets.findAll().getFirst();
        a.setAssignedUserId(owner.getId()); a.setAssignedTo(owner.getDisplayName()); a.setManufacturerSerialNumber("REG-EXISTING"); assets.saveAndFlush(a);
        var result = submit(owner, body("REG-EXISTING")); String before = snapshot();
        var approved = action(admin(), result, "approve-existing", Map.of("assetId", a.getId(), "assetVersion", a.getVersion(), "confirmed", true));
        assertEquals(a.getId(), approved.path("request").path("approvedAssetId").asLong()); assertEquals(before, snapshot());
        var mismatch = submit(owner, body("DIFFERENT-SN"));
        mvc.perform(post("/api/registrations/{id}/approve-existing", mismatch.path("request").path("id").asLong()).with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", mismatch.path("request").path("version").asLong(), "assetId", a.getId(), "assetVersion", a.getVersion(), "confirmed", true)))).andExpect(status().isConflict());
        assertEquals(before, snapshot());
    }
    @Test void approvalRequiresFreshPermissionAndExplicitConfirmation() throws Exception {
        var owner = employee("registration-permission"); var result = submit(owner, body("REG-PERMISSION")); var a = assets.findAll().getFirst();
        var payload = Map.of("version", result.path("request").path("version").asLong(), "assetId", a.getId(), "assetVersion", a.getVersion(), "confirmed", true);
        mvc.perform(post("/api/registrations/{id}/approve-existing", result.path("request").path("id").asLong()).with(user(AccountPrincipal.from(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload))).andExpect(status().isForbidden());
        var reviewer = employee("registration-reviewer"); reviewer.setDataScope(DataScope.ALL); reviewer.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_CREATE, Permission.ASSET_CHECKOUT)); users.saveAndFlush(reviewer); var principal = AccountPrincipal.from(reviewer);
        reviewer.setPermissions(Set.of(Permission.ASSET_VIEW)); users.saveAndFlush(reviewer);
        mvc.perform(get("/api/registrations").param("review", "true").with(user(principal))).andExpect(status().isForbidden());
        var unchecked = new LinkedHashMap<String, Object>(payload); unchecked.put("confirmed", false);
        mvc.perform(post("/api/registrations/{id}/approve-existing", result.path("request").path("id").asLong()).with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(unchecked))).andExpect(status().isBadRequest());
    }
}
