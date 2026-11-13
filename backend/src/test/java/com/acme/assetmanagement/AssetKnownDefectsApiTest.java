package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.Asset;
import com.acme.assetmanagement.asset.AssetRepository;
import com.acme.assetmanagement.lookup.LookupRepository;
import com.acme.assetmanagement.lookup.LookupType;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.UserAccount;
import com.acme.assetmanagement.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "app.asset-images.directory=${java.io.tmpdir}/asset-management-test-images"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AssetKnownDefectsApiTest {
    @Autowired MockMvc mvc;
    @Autowired AssetRepository assets;
    @Autowired LookupRepository lookups;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired com.acme.assetmanagement.audit.AuditLogRepository audit;

    @Test
    void createsDefectsWithServerMetadataAndKeepsNotesSeparate() throws Exception {
        var body = base();
        body.put("knownDefects", List.of(Map.of("description", " P键失灵 ", "recordedBy", "伪造用户"),
                Map.of("description", "Type-C无法充电")));
        var response = create(body);
        assertEquals("P键失灵", response.at("/knownDefects/0/description").asText());
        assertEquals("defect-tester", response.at("/knownDefects/0/recordedBy").asText());
        assertEquals(36, response.at("/knownDefects/0/id").asText().length());
        assertTrue(response.at("/knownDefects/0").hasNonNull("recordedAt"));
        assertEquals("保修资料", response.get("notes").asText());
        assertFalse(response.get("checkedOut").asBoolean());
        long id = response.get("id").asLong();
        em.clear();
        assertEquals(2, assets.findById(id).orElseThrow().getKnownDefects().size());
        mvc.perform(get("/api/assets").with(editor())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].knownDefects[0].description").value(org.hamcrest.Matchers.hasItem("P键失灵")));
        assertTrue(audit.findFirst100ByTargetTypeAndTargetIdOrderByOccurredAtDesc("ASSET", id).stream()
                .anyMatch(entry -> entry.getChangesJson().contains("设备已知缺陷")));
    }

    @Test
    void archivesAndRestoresWithoutChangingLifecycleAndIncrementsVersion() throws Exception {
        var body = base();
        var created = create(body);
        long id = created.get("id").asLong();
        var asset = assets.findById(id).orElseThrow();
        asset.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "在用").orElseThrow());
        asset.setCheckedOut(true);
        asset.setAssignedTo("现有领用人");
        asset.setDomainJoinStatus(com.acme.assetmanagement.asset.DomainJoinStatus.JOINED);
        assets.saveAndFlush(asset);
        body.put("assetTag", asset.getAssetTag());
        body.put("statusId", asset.getStatus().getId());
        body.put("assignedTo", asset.getAssignedTo());
        body.put("checkedOut", true);
        body.put("domainJoinStatus", "JOINED");
        body.put("knownDefects", List.of(Map.of("description", "Type-C不能充电")));
        long previousVersion = asset.getVersion();
        var recorded = update(id, previousVersion, body);
        assertTrue(recorded.get("version").asLong() > previousVersion);
        String defectId = recorded.at("/knownDefects/0/id").asText();
        var recordedAt = recorded.at("/knownDefects/0/recordedAt");
        body.put("knownDefects", List.of(Map.of("id", defectId, "description", "Type-C只能传数据，不能充电", "archived", true)));
        var archived = update(id, recorded.get("version").asLong(), body);
        assertTrue(archived.at("/knownDefects/0").hasNonNull("archivedAt"));
        assertEquals("defect-tester", archived.at("/knownDefects/0/archivedBy").asText());
        assertEquals(recordedAt, archived.at("/knownDefects/0/recordedAt"));
        assertTrue(archived.get("checkedOut").asBoolean());
        assertEquals("现有领用人", archived.get("assignedTo").asText());
        assertEquals("JOINED", archived.get("domainJoinStatus").asText());
        body.put("knownDefects", List.of(Map.of("id", defectId, "description", "Type-C只能传数据，不能充电", "archived", false)));
        var restored = update(id, archived.get("version").asLong(), body);
        assertFalse(restored.at("/knownDefects/0").hasNonNull("archivedAt"));
        assertEquals(recordedAt, restored.at("/knownDefects/0/recordedAt"));
        em.clear();
        assertNull(assets.findById(id).orElseThrow().getKnownDefects().getFirst().getArchivedAt());
    }

    @Test
    void legacyUpdateKeepsDefectsAndStaleWritesAreRejected() throws Exception {
        var body = base();
        body.put("knownDefects", List.of(Map.of("description", "屏幕有亮点")));
        var created = create(body);
        long id = created.get("id").asLong();
        body.put("assetTag", created.get("assetTag").asText());
        body.remove("knownDefects");
        body.put("notes", "更新保修资料");
        var changed = update(id, created.get("version").asLong(), body);
        assertEquals("屏幕有亮点", changed.at("/knownDefects/0/description").asText());
        mvc.perform(put("/api/assets/{id}", id).with(editor()).with(csrf())
                        .header("If-Match", created.get("version").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body))).andExpect(status().isConflict());
        mvc.perform(put("/api/assets/{id}", id).with(editor()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void rejectsErasureDuplicateAndForeignIdsWithoutLosingStoredRecords() throws Exception {
        var body = base();
        body.put("knownDefects", List.of(Map.of("description", "键盘失灵")));
        var created = create(body);
        long id = created.get("id").asLong();
        body.put("assetTag", created.get("assetTag").asText());
        var record = Map.of("id", created.at("/knownDefects/0/id").asText(), "description", "键盘失灵");
        for (var invalid : List.of(List.of(), List.of(record, record), List.of(Map.of("id", "other-asset-id", "description", "未知记录")))) {
            body.put("knownDefects", invalid);
            mvc.perform(put("/api/assets/{id}", id).with(editor()).with(csrf())
                            .header("If-Match", created.get("version").asLong()).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
            assertEquals(1, assets.findById(id).orElseThrow().getKnownDefects().size());
        }
    }

    @Test
    void validatesDescriptionsAndCollectionSizeBeforeCreating() throws Exception {
        var invalidLists = new ArrayList<List<?>>();
        invalidLists.add(java.util.Arrays.asList((Object) null));
        invalidLists.add(List.of(Map.of("description", "   ")));
        invalidLists.add(List.of(Map.of("description", "a".repeat(301))));
        invalidLists.add(List.of(Map.of("description", "含\u0000控制字符")));
        invalidLists.add(List.of(Map.of("description", "新记录", "archived", true)));
        invalidLists.add(java.util.Collections.nCopies(51, Map.of("description", "重复")));
        long count = assets.count();
        for (var invalid : invalidLists) {
            var body = base();
            body.put("knownDefects", invalid);
            mvc.perform(post("/api/assets").with(editor()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
            assertEquals(count, assets.count());
        }
    }

    @Test
    void requiresEditPermission() throws Exception {
        var body = base();
        var created = create(body);
        body.put("assetTag", created.get("assetTag").asText());
        body.put("knownDefects", List.of(Map.of("description", "无权修改")));
        mvc.perform(put("/api/assets/{id}", created.get("id").asLong()).with(user("readonly").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ASSET_VIEW"))).with(csrf())
                .header("If-Match", created.get("version").asLong()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isForbidden());
    }

    private Map<String, Object> base() {
        Asset seed = assets.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "已知缺陷测试电脑");
        body.put("companyId", seed.getCompany().getId());
        body.put("modelId", seed.getModel().getId());
        body.put("categoryId", seed.getCategory().getId());
        body.put("statusId", lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用").orElseThrow().getId());
        body.put("locationId", seed.getLocation().getId());
        body.put("notes", "保修资料");
        body.put("checkedOut", false);
        return body;
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        return json.readTree(mvc.perform(post("/api/assets").with(editor()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode update(long id, long version, Map<String, Object> body) throws Exception {
        return json.readTree(mvc.perform(put("/api/assets/{id}", id).with(editor()).with(csrf()).header("If-Match", version)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private RequestPostProcessor editor() {
        var account = users.findByUsernameIgnoreCase("defect-tester").orElseGet(() -> {
            var user = new UserAccount();
            user.setUsername("defect-tester"); user.setDisplayName("测试用户"); user.setPasswordHash("test-hash");
            return users.saveAndFlush(user);
        });
        return user(AccountPrincipal.from(account));
    }
}
