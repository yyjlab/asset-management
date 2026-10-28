package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.audit.AuditLogRepository;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real commits/rollbacks, not a wrapping test transaction. No production database is used. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:asset-bundle-transactions;DB_CLOSE_DELAY=-1",
        "app.asset-images.directory=${java.io.tmpdir}/asset-bundle-test-images"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssetBundleApiTest {
    @Autowired MockMvc mvc;
    @Autowired AssetRepository assets;
    @Autowired LookupRepository lookups;
    @Autowired UserRepository users;
    @Autowired AssetBundleSubmissionRepository submissions;
    @Autowired AuditLogRepository audits;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    private RequestPostProcessor admin() { return user(AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow())); }
    private Map<String, Object> device(boolean display) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var seed = assets.findAll().getFirst();
            var category = lookups.findAll().stream().filter(v -> v.getType() == LookupType.CATEGORY
                    && (v.getAssetProfile() == (display ? AssetProfile.DISPLAY : AssetProfile.COMPUTER))).findFirst()
                    .orElseGet(() -> lookups.saveAndFlush(new LookupValue(LookupType.CATEGORY, "整套测试显示器", AssetProfile.DISPLAY)));
            var status = lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用")
                    .or(() -> lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "当前可用")).orElseThrow();
            var data = new LinkedHashMap<String, Object>();
            data.put("assetTag", ""); data.put("name", display ? "显示器" : "笔记本电脑");
            data.put("companyId", seed.getCompany().getId()); data.put("categoryId", category.getId());
            data.put("statusId", status.getId()); data.put("locationId", seed.getLocation().getId());
            data.put("modelName", display ? "AOC-27-BUNDLE" : "ThinkPad-BUNDLE");
            data.put("manufacturerSerialNumber", UUID.randomUUID().toString()); data.put("checkedOut", false);
            data.put("boundDisplayIds", List.of()); data.put("imageUrls", List.of());
            data.put("ownershipDepartment", "整套测试部门"); data.put("purchasePrice", display ? 629 : 3000);
            if (display) { data.put("screenSize", "27英寸"); data.put("displayResolution", "2K"); }
            return data;
        });
    }
    private Map<String, Object> bundle(int count) {
        var body = new LinkedHashMap<String, Object>(); body.put("requestKey", UUID.randomUUID().toString());
        body.put("computer", device(false));
        var displays = new ArrayList<Map<String, Object>>(); for (int i = 0; i < count; i++) displays.add(device(true));
        body.put("displays", displays); body.put("existingDisplays", List.of()); return body;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> computer(Map<String, Object> body) { return (Map<String, Object>) body.get("computer"); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> displays(Map<String, Object> body) { return (List<Map<String, Object>>) body.get("displays"); }
    private JsonNode save(Map<String, Object> body, int expected) throws Exception {
        return json.readTree(mvc.perform(post("/api/assets/bundles").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private JsonNode create(Map<String, Object> body) throws Exception {
        return json.readTree(mvc.perform(post("/api/assets").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getAsset(long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/assets/{id}", id).with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
    private RequestPostProcessor restricted(Set<Permission> permissions, DataScope scope) {
        var account = new UserAccount(); account.setUsername("bundle-" + UUID.randomUUID()); account.setDisplayName("整套测试员工");
        account.setPasswordHash("not-a-password"); account.setAccessConfigured(true); account.setPermissions(permissions); account.setDataScope(scope);
        return user(AccountPrincipal.from(users.saveAndFlush(account)));
    }
    private String photo() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB); image.setRGB(0, 0, UUID.randomUUID().hashCode());
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    private Set<String> imageFiles() throws Exception {
        var directory = Path.of(System.getProperty("java.io.tmpdir"), "asset-bundle-test-images");
        if (!Files.exists(directory)) return Set.of();
        try (var files = Files.walk(directory)) { return files.filter(Files::isRegularFile).map(Path::toString).collect(java.util.stream.Collectors.toSet()); }
    }

    @Test void createsSeparateAssetsWithIndependentLabelsPhotosSerialsPricesAndBindings() throws Exception {
        mvc.perform(put("/api/settings/qr").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrBaseUrl\":\"https://asset.example.test\"}")).andExpect(status().isOk());
        var body = bundle(2); computer(body).put("imageUrls", List.of(photo())); displays(body).getFirst().put("imageUrls", List.of(photo()));
        long before = assets.count(); var result = save(body, 200); var pc = result.get("computer");
        assertEquals(before + 3, assets.count()); assertFalse(result.get("replayed").asBoolean());
        assertEquals(2, pc.get("boundDisplays").size()); assertEquals(3000, pc.get("purchasePrice").asInt());
        var tags = new HashSet<String>(); tags.add(pc.get("assetTag").asText());
        for (var display : result.get("displays")) {
            assertTrue(tags.add(display.get("assetTag").asText())); assertEquals(629, display.get("purchasePrice").asInt());
            assertEquals(pc.get("id").asLong(), display.get("boundComputer").get("id").asLong());
            mvc.perform(get("/api/assets/{id}/qr", display.get("id").asLong()).with(admin())).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        }
        assertNotEquals(pc.get("imageUrls").get(0).asText(), result.get("displays").get(0).get("imageUrls").get(0).asText());
        assertEquals(computer(body).get("manufacturerSerialNumber"), pc.get("manufacturerSerialNumber").asText());
        assertEquals(getAsset(pc.get("id").asLong()).get("version"), pc.get("version"));
    }
    @Test void lateSerialConflictRollsBackAllAssetsPhotosSuggestionsAndCompletedKey() throws Exception {
        var body = bundle(2); displays(body).get(1).put("manufacturerSerialNumber", displays(body).getFirst().get("manufacturerSerialNumber"));
        computer(body).put("imageUrls", List.of(photo())); displays(body).getFirst().put("imageUrls", List.of(photo()));
        computer(body).put("modelName", "ROLLBACK-" + UUID.randomUUID());
        long before = assets.count(), keys = submissions.count(), lookupCount = lookups.count(); var files = imageFiles();
        save(body, 409);
        assertEquals(before, assets.count()); assertEquals(keys, submissions.count()); assertEquals(files, imageFiles());
        assertEquals(lookupCount, lookups.count());
        displays(body).get(1).put("manufacturerSerialNumber", UUID.randomUUID().toString());
        save(body, 200); assertEquals(before + 3, assets.count());
    }
    @Test void exactRetryAndRecoveryReturnSameIdsAndNoAdditionalWrites() throws Exception {
        var body = bundle(1); var first = save(body, 200); long count = assets.count(), auditCount = audits.count();
        var repeated = save(body, 200); assertTrue(repeated.get("replayed").asBoolean());
        assertEquals(first.get("computer").get("id"), repeated.get("computer").get("id"));
        assertEquals(count, assets.count()); assertEquals(auditCount, audits.count());
        mvc.perform(get("/api/assets/bundles/{key}", body.get("requestKey")).with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.computer.id").value(first.get("computer").get("id").asLong()));
        computer(body).put("notes", "重试不能改变已提交内容"); save(body, 409); assertEquals(count, assets.count());
    }
    @Test void concurrentIdenticalSubmissionsCommitOneSetOnly() throws Exception {
        var body = bundle(1); long count = assets.count(); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> save(body, 200)); var b = pool.submit(() -> save(body, 200));
            assertEquals(a.get(25, TimeUnit.SECONDS).get("computer").get("id"), b.get(25, TimeUnit.SECONDS).get("computer").get("id"));
            assertEquals(count + 2, assets.count());
        } finally { pool.shutdownNow(); }
    }
    @Test void existingMonitorOnlyChangesBindingNotHistoricalDetails() throws Exception {
        var old = device(true); old.put("notes", "原有显示器记录"); old.put("imageUrls", List.of(photo()));
        var monitor = create(old); var body = bundle(1); computer(body).put("boundDisplayIds", List.of(monitor.get("id").asLong()));
        body.put("existingDisplays", List.of(Map.of("id", monitor.get("id").asLong(), "version", monitor.get("version").asLong())));
        var result = save(body, 200); var after = getAsset(monitor.get("id").asLong());
        for (String field : List.of("name", "model", "manufacturerSerialNumber", "imageUrls", "notes", "assignedTo", "checkedOut", "ownershipDepartment"))
            assertEquals(monitor.get(field), after.get(field), field);
        assertEquals(0, monitor.get("purchasePrice").decimalValue().compareTo(after.get("purchasePrice").decimalValue()));
        assertEquals(result.get("computer").get("id"), after.get("boundComputer").get("id"));
    }
    @Test void staleComputerVersionDoesNotCreateAnyMonitor() throws Exception {
        var data = device(false); var pc = create(data); var body = bundle(1);
        body.put("computerId", pc.get("id").asLong()); body.put("computerVersion", pc.get("version").asLong() + 1);
        data.put("assetTag", pc.get("assetTag").asText()); body.put("computer", data); long count = assets.count();
        save(body, 409); assertEquals(count, assets.count());
    }
    @Test void lateFailureWhileEditingPreservesOldComputerPhotoVersionAndAllOriginalFields() throws Exception {
        var data = device(false); data.put("imageUrls", List.of(photo())); data.put("notes", "原有生产档案的测试副本");
        var pc = create(data); var body = bundle(2); body.put("computerId", pc.get("id").asLong()); body.put("computerVersion", pc.get("version").asLong());
        data.put("assetTag", pc.get("assetTag").asText()); data.put("notes", "不应落库的修改"); data.put("imageUrls", List.of(photo())); body.put("computer", data);
        displays(body).get(1).put("manufacturerSerialNumber", displays(body).getFirst().get("manufacturerSerialNumber"));
        long count = assets.count(); var files = imageFiles(); save(body, 409);
        var after = getAsset(pc.get("id").asLong()); assertEquals(count, assets.count()); assertEquals(files, imageFiles());
        for (String field : List.of("assetTag", "name", "notes", "imageUrls", "version", "boundDisplays", "assignedTo")) assertEquals(pc.get(field), after.get(field), field);
    }
    @Test void staleBoundOrOutOfScopeExistingMonitorCannotBeReassigned() throws Exception {
        var first = save(bundle(1), 200); var monitor = first.get("displays").get(0);
        var body = bundle(0); computer(body).put("boundDisplayIds", List.of(monitor.get("id").asLong()));
        body.put("existingDisplays", List.of(Map.of("id", monitor.get("id").asLong(), "version", monitor.get("version").asLong())));
        long count = assets.count(); save(body, 409); assertEquals(count, assets.count());
        body.put("existingDisplays", List.of(Map.of("id", monitor.get("id").asLong(), "version", monitor.get("version").asLong() + 1)));
        save(body, 409); assertEquals(count, assets.count());
    }
    @Test void permissionsScopeCsrfAndOwnerAreEnforced() throws Exception {
        var body = bundle(1); var limited = restricted(Set.of(Permission.ASSET_VIEW, Permission.ASSET_CREATE), DataScope.ALL);
        mvc.perform(post("/api/assets/bundles").with(limited).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        mvc.perform(post("/api/assets/bundles").with(admin()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        var result = save(body, 200); var other = restricted(Set.of(Permission.ASSET_VIEW, Permission.ASSET_CREATE, Permission.ASSET_EDIT), DataScope.ALL);
        mvc.perform(get("/api/assets/bundles/{key}", body.get("requestKey")).with(other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/assets/bundles").with(other).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isConflict());
        var self = restricted(Set.of(Permission.ASSET_VIEW, Permission.ASSET_CREATE, Permission.ASSET_EDIT), DataScope.SELF);
        var outOfScope = bundle(0); var monitor = result.get("displays").get(0);
        computer(outOfScope).put("boundDisplayIds", List.of(monitor.get("id").asLong())); outOfScope.put("existingDisplays", List.of(Map.of("id", monitor.get("id").asLong(), "version", monitor.get("version").asLong())));
        mvc.perform(post("/api/assets/bundles").with(self).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(outOfScope))).andExpect(status().isNotFound());
    }
    @Test void deletedAssetIsNeverRecreatedOnReplay() throws Exception {
        var body = bundle(1); var first = save(body, 200); long id = first.get("displays").get(0).get("id").asLong();
        mvc.perform(delete("/api/assets/{id}", id).with(admin()).with(csrf()).header("If-Match", getAsset(id).get("version").asLong())).andExpect(status().isNoContent());
        long count = assets.count(); save(body, 404); assertEquals(count, assets.count());
        mvc.perform(get("/api/assets/bundles/{key}", body.get("requestKey")).with(admin())).andExpect(status().isNotFound());
    }
    @Test void wrongProfilesMissingReviewedVersionsAndOversizedSetsAreRejected() throws Exception {
        var body = bundle(1); displays(body).getFirst().put("categoryId", computer(body).get("categoryId")); save(body, 400);
        body = bundle(1); computer(body).put("boundDisplayIds", List.of(1L)); save(body, 400);
        body = bundle(1); body.put("existingDisplays", List.of(Map.of("id", 1L))); save(body, 400);
        body = bundle(9); save(body, 400);
        body = bundle(0); save(body, 400);
    }
}
