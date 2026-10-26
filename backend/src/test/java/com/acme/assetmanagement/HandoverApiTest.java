package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.handover.HandoverRepository;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:handoverdb;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HandoverApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Autowired LookupRepository lookups;
    @Autowired HandoverRepository tasks;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    private UserAccount admin() { return users.findByUsernameIgnoreCase("admin").orElseThrow(); }
    private UserAccount employee(String username) {
        var account = new UserAccount(); account.setUsername(username); account.setDisplayName("同名员工");
        account.setPasswordHash(encoder.encode("HandoverTest123!")); account.setAccessConfigured(true);
        account.setPermissions(Set.of(Permission.ASSET_VIEW)); account.setDataScope(DataScope.SELF);
        return users.saveAndFlush(account);
    }
    private Asset asset(String tag, UserAccount employee) {
        var seed = assets.findAll().stream().findFirst().orElseThrow();
        var asset = new Asset(); asset.setAssetTag(tag); asset.setName("电脑交接测试");
        asset.setCompany(seed.getCompany()); asset.setModel(seed.getModel()); asset.setCategory(seed.getCategory());
        asset.setStatus(seed.getStatus()); asset.setLocation(seed.getLocation());
        asset.setCpu("历史CPU不可改"); asset.setMemory("32GB"); asset.setNotes("历史备注不可改");
        asset.setOwnershipDepartment("研发部"); asset.setManufacturerSerialNumber("SN-" + tag);
        asset.setPurchasePrice(new java.math.BigDecimal("6234.56"));
        asset.setCurrentValue(new java.math.BigDecimal("4321.00"));
        asset.setImageUrl("/api/public/asset-images/historical-handover-test.png");
        asset.getAccessories().add(new AssetAccessory("适配器", "原装", 1));
        if (employee != null) { asset.setAssignedUserId(employee.getId()); asset.setAssignedTo(employee.getDisplayName()); asset.setCheckedOut(true); }
        return assets.saveAndFlush(asset);
    }
    private Map<String, Object> request(String kind, UserAccount employee) {
        var body = new LinkedHashMap<String, Object>();
        body.put("requestKey", UUID.randomUUID().toString()); body.put("kind", kind);
        body.put("employeeUserId", employee == null ? null : employee.getId());
        body.put("employeeName", employee == null ? "未建账号的新员工" : "不可用于覆盖账号姓名");
        body.put("department", "研发部"); body.put("plannedDate", "2026-11-01"); body.put("approvalReference", "APPROVAL-TEST-001");
        body.put("notes", "只记录设备交接"); body.put("assetIds", List.of()); body.put("includeAssignedAssets", false);
        body.put("allowLegacyAssets", false); body.put("manualItems", List.of("核对办公电脑"));
        return body;
    }
    private JsonNode create(Map<String, Object> body) throws Exception {
        return json.readTree(mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode action(JsonNode task, String path, UserAccount actor, Map<String, Object> extra) throws Exception {
        var body = new LinkedHashMap<String, Object>(extra); body.put("version", task.get("version").asLong());
        return json.readTree(mvc.perform(post("/api/handovers/" + task.get("id").asLong() + path)
                .with(user(AccountPrincipal.from(actor))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String assetSnapshot() throws Exception {
        return mvc.perform(get("/api/assets").with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private Asset availableAsset(String tag) {
        var device = asset(tag, null);
        device.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "当前可用")
                .or(() -> lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用")).orElseThrow());
        return assets.saveAndFlush(device);
    }
    private Map<String, Object> integrated(String kind, UserAccount employee, Asset... devices) {
        var body = request(kind, employee); body.put("ledgerEnabled", true); body.put("manualItems", List.of());
        body.put("assetIds", Arrays.stream(devices).map(Asset::getId).toList());
        var versions = new LinkedHashMap<Long, Long>();
        for (var device : devices) versions.put(device.getId(), device.getVersion());
        body.put("assetVersions", versions); return body;
    }
    private JsonNode signed(JsonNode task, UserAccount employee) throws Exception {
        for (int index = 0; index < task.get("items").size(); index++) {
            task = action(task, "/items/" + index + "/check", admin(), Map.of("checked", true));
            task = action(task, "/items/" + index + "/confirm", employee, Map.of("offline", false));
        }
        return task;
    }

    @Test void integratedOnboardingWritesCustodyOnlyAfterFinalConfirmationAndLinksOperations() throws Exception {
        var owner = employee("integrated-onboard"); var first = availableAsset("HI-001"); var second = availableAsset("HI-002");
        var task = create(integrated("ONBOARDING", owner, first, second));
        assertTrue(task.get("ledgerEnabled").asBoolean()); assertTrue(task.get("ledgerAppliedAt").isNull());
        assertNull(assets.findById(first.getId()).orElseThrow().getAssignedUserId());
        long operation = task.get("items").get(0).get("operationId").asLong();
        mvc.perform(get("/api/operations/{id}", operation).with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.state").value("HANDOVER_PENDING"))
                .andExpect(jsonPath("$.task.handoverId").value(task.get("id").asLong()));
        task = signed(task, owner);
        assertFalse(assets.findById(first.getId()).orElseThrow().isCheckedOut());
        task = action(task, "/complete", admin(), Map.of("confirmed", true));
        assertEquals("COMPLETED", task.get("state").asString()); assertFalse(task.get("ledgerAppliedAt").isNull());
        for (var device : List.of(first, second)) {
            var actual = assets.findById(device.getId()).orElseThrow();
            assertEquals(owner.getId(), actual.getAssignedUserId()); assertTrue(actual.isCheckedOut());
            assertTrue(Set.of("已经领出", "在用").contains(actual.getStatus().getName()));
            assertEquals("历史CPU不可改", actual.getCpu()); assertEquals("32GB", actual.getMemory());
            assertEquals("历史备注不可改", actual.getNotes()); assertEquals("研发部", actual.getOwnershipDepartment());
            assertEquals(new java.math.BigDecimal("6234.56"), actual.getPurchasePrice());
            assertEquals(new java.math.BigDecimal("4321.00"), actual.getCurrentValue());
            assertEquals("/api/public/asset-images/historical-handover-test.png", actual.getImageUrl());
            assertEquals(1, actual.getAccessories().size());
        }
        mvc.perform(get("/api/operations/{id}", operation).with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.state").value("COMPLETED"));
    }

    @Test void integratedReturnSupportsDisabledEmployeeOfflineProofAndPreservesMaintenance() throws Exception {
        var owner = employee("integrated-offboard"); var other = employee("integrated-other");
        var device = asset("HI-003", owner); var untouched = asset("HI-004", other);
        device.setStatus(lookups.findByTypeOrderByNameAsc(LookupType.STATUS).stream()
                .filter(value -> value.getName().contains("维修") || value.getName().contains("维护")).findFirst().orElseThrow());
        assets.saveAndFlush(device); var statusId = device.getStatus().getId();
        owner.setEnabled(false); users.saveAndFlush(owner);
        var task = create(integrated("OFFBOARDING", owner, device));
        task = action(task, "/items/0/check", admin(), Map.of("checked", true));
        task = action(task, "/items/0/confirm", admin(), Map.of("offline", true, "note", "纸质归还签收单 HI-003"));
        action(task, "/complete", admin(), Map.of("confirmed", true));
        assertNull(device.getAssignedUserId()); assertNull(device.getAssignedTo()); assertFalse(device.isCheckedOut());
        assertEquals(statusId, device.getStatus().getId()); assertEquals(other.getId(), untouched.getAssignedUserId());
        assertFalse(users.findById(owner.getId()).orElseThrow().isEnabled());
    }

    @Test void integratedFinalWriteRequiresExplicitConsentAndCannotBeDuplicated() throws Exception {
        var owner = employee("integrated-consent"); var device = availableAsset("HI-005");
        var task = signed(create(integrated("ONBOARDING", owner, device)), owner);
        mvc.perform(post("/api/handovers/{id}/complete", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + task.get("version").asLong() + "}"))
                .andExpect(status().isBadRequest());
        assertFalse(device.isCheckedOut());
        var done = action(task, "/complete", admin(), Map.of("confirmed", true));
        mvc.perform(post("/api/handovers/{id}/complete", done.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + done.get("version").asLong() + ",\"confirmed\":true}"))
                .andExpect(status().isConflict());
    }

    @Test void integratedReservationsRejectDuplicateAndOperationActionsThenCancelReleases() throws Exception {
        var owner = employee("integrated-reservation"); var device = availableAsset("HI-006");
        var task = create(integrated("ONBOARDING", owner, device));
        long operation = task.get("items").get(0).get("operationId").asLong();
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(integrated("ONBOARDING", owner, device))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/operations/{id}/actions", operation).with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"action\":\"CANCEL\",\"note\":\"不能绕开交接\"}"))
                .andExpect(status().isConflict());
        action(task, "/cancel", admin(), Map.of("note", "设备计划调整"));
        assertFalse(device.isCheckedOut());
        mvc.perform(get("/api/operations/{id}", operation).with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.state").value("CANCELLED"));
        create(integrated("ONBOARDING", owner, device));
    }

    @Test void integratedPreparationCanBindDeviceAndAccountWithoutMatchingEmployeeName() throws Exception {
        var owner = employee("integrated-later"); var other = employee("integrated-same-name"); var device = availableAsset("HI-007");
        var body = integrated("ONBOARDING", null); body.put("manualItems", List.of("准备办公电脑"));
        var task = create(body);
        mvc.perform(post("/api/handovers/{id}/items/0/check", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + task.get("version").asLong() + ",\"checked\":true}"))
                .andExpect(status().isConflict());
        task = action(task, "/items/0/asset", admin(), Map.of("assetId", device.getId(), "assetVersion", device.getVersion()));
        task = action(task, "/employee", admin(), Map.of("employeeUserId", owner.getId()));
        mvc.perform(get("/api/handovers/{id}", task.get("id").asLong()).with(user(AccountPrincipal.from(other))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/operations/{id}", task.get("items").get(0).get("operationId").asLong()).with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.targetId").value(owner.getId()));
        action(signed(task, owner), "/complete", admin(), Map.of("confirmed", true));
        assertEquals(owner.getId(), device.getAssignedUserId());
    }

    @Test void integratedSelfRecipientCannotPerformPhysicalCheckOrForgeOfflineProof() throws Exception {
        var device = availableAsset("HI-008"); var task = create(integrated("ONBOARDING", admin(), device));
        mvc.perform(post("/api/handovers/{id}/items/0/check", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + task.get("version").asLong() + ",\"checked\":true}"))
                .andExpect(status().isForbidden());
        var manager = employee("integrated-second-manager"); manager.setDataScope(DataScope.ALL);
        manager.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN)); users.saveAndFlush(manager);
        task = action(task, "/items/0/check", manager, Map.of("checked", true));
        mvc.perform(post("/api/handovers/{id}/items/0/confirm", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + task.get("version").asLong() + ",\"offline\":true,\"note\":\"不能为自己代填\"}"))
                .andExpect(status().isForbidden());
        task = action(task, "/items/0/confirm", admin(), Map.of("offline", false));
        action(task, "/complete", manager, Map.of("confirmed", true));
        assertEquals(admin().getId(), device.getAssignedUserId());
    }

    @Test void integratedCreateRequiresReviewedDeviceVersionAndManualOnlyCompletionHasNoLedgerClaim() throws Exception {
        var owner = employee("integrated-versions"); var device = availableAsset("HI-009");
        var body = integrated("ONBOARDING", owner, device); body.remove("assetVersions");
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isConflict());
        body = integrated("ONBOARDING", owner); body.put("manualItems", List.of("核对电源适配器"));
        var task = signed(create(body), owner);
        task = action(task, "/complete", admin(), Map.of("confirmed", true));
        assertTrue(task.get("ledgerAppliedAt").isNull()); assertFalse(device.isCheckedOut());
    }

    @Test void handoverAndRegularWorkflowShareTheSameExclusiveDeviceReservation() throws Exception {
        var owner = employee("integrated-regular"); var device = availableAsset("HI-010");
        var task = create(integrated("ONBOARDING", owner, device));
        var operation = json.readTree(mvc.perform(post("/api/operations").with(user(AccountPrincipal.from(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("requestKey", UUID.randomUUID().toString(),
                        "kind", "ISSUE", "title", "申请电脑", "description", "正常领用申请"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var approve = Map.of("version", operation.get("task").get("version").asLong(), "action", "APPROVE",
                "assetId", device.getId(), "assetVersion", device.getVersion());
        mvc.perform(post("/api/operations/{id}/actions", operation.get("task").get("id").asLong())
                .with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(approve))).andExpect(status().isConflict());
        action(task, "/cancel", admin(), Map.of("note", "改用正常领用申请"));
        mvc.perform(post("/api/operations/{id}/actions", operation.get("task").get("id").asLong())
                .with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(approve))).andExpect(status().isOk());
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(integrated("ONBOARDING", owner, device))))
                .andExpect(status().isConflict());
        assertFalse(device.isCheckedOut());
    }

    @Test void integratedLegacyReturnRequiresExplicitAcknowledgementAndDoesNotInferAnotherSameNameAccount() throws Exception {
        var owner = employee("integrated-legacy"); var other = employee("integrated-legacy-other");
        var legacy = asset("HI-011", null); legacy.setAssignedTo(owner.getDisplayName()); legacy.setCheckedOut(true); assets.saveAndFlush(legacy);
        var otherDevice = asset("HI-012", other);
        var body = integrated("OFFBOARDING", owner, legacy);
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.put("allowLegacyAssets", true);
        var task = signed(create(body), owner);
        action(task, "/complete", admin(), Map.of("confirmed", true));
        assertFalse(legacy.isCheckedOut()); assertNull(legacy.getAssignedTo());
        assertEquals(other.getId(), otherDevice.getAssignedUserId()); assertTrue(otherDevice.isCheckedOut());
    }

    @Test void anonymousCannotReadOrCreateHandovers() throws Exception {
        mvc.perform(get("/api/handovers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/handovers/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/handovers").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request("ONBOARDING", null)))).andExpect(status().isUnauthorized());
        assertEquals(0, tasks.count());
    }

    @Test void offboardingAutoListUsesStableAccountIdNotDisplayNameAndDoesNotModifyAssets() throws Exception {
        var owner = employee("handover-owner"); var sameName = employee("handover-other");
        var own = asset("HO-001", owner); asset("HO-002", sameName);
        var legacy = asset("HO-003", null); legacy.setAssignedTo(owner.getDisplayName()); legacy.setCheckedOut(true); assets.saveAndFlush(legacy);
        String before = assetSnapshot(); var accountUpdatedAt = owner.getUpdatedAt();
        var body = request("OFFBOARDING", owner); body.put("includeAssignedAssets", true); body.put("manualItems", List.of());
        var task = create(body);
        assertEquals(owner.getDisplayName(), task.get("employeeName").asString());
        assertEquals(1, task.get("items").size());
        assertEquals(own.getId(), task.get("items").get(0).get("assetId").asLong());
        assertTrue(task.get("items").get(0).get("accessories").asString().contains("适配器"));
        assertEquals(before, assetSnapshot()); assertEquals(accountUpdatedAt, users.findById(owner.getId()).orElseThrow().getUpdatedAt());
    }

    @Test void employeeSeesOnlyOwnTasksAndCannotReadPeopleOrManageOthers() throws Exception {
        var owner = employee("task-owner"); var other = employee("task-outsider");
        var task = create(request("ONBOARDING", owner));
        mvc.perform(get("/api/handovers").with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/handovers").with(user(AccountPrincipal.from(other))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/handovers/{id}", task.get("id").asLong()).with(user(AccountPrincipal.from(other))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/handovers/employees").with(user(AccountPrincipal.from(owner)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request("ONBOARDING", owner)))).andExpect(status().isForbidden());
    }

    @Test void managerSelfServiceUsesOwnAccountBeforePaginationAndDoesNotModifyAssets() throws Exception {
        var other = employee("self-service-other");
        String before = assetSnapshot();
        var own = create(request("ONBOARDING", admin()));
        create(request("OFFBOARDING", admin()));
        create(request("ONBOARDING", null));
        for (int index = 0; index < 21; index++) create(request("ONBOARDING", other));

        mvc.perform(get("/api/handovers").param("mine", "true").with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].employeeUserId").value(admin().getId()))
                .andExpect(jsonPath("$.items[1].employeeUserId").value(admin().getId()));
        mvc.perform(get("/api/handovers").param("mine", "true").param("kind", "ONBOARDING")
                .param("state", "PENDING").with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(own.get("id").asLong()));
        mvc.perform(get("/api/handovers").param("mine", "true").param("page", "1")
                .with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/handovers").with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.totalElements").value(24));
        assertEquals(before, assetSnapshot());
    }

    @Test void selfServiceCannotUseQueryParametersToReadAnotherEmployeeAndRequiresLogin() throws Exception {
        var owner = employee("self-service-owner"); var other = employee("self-service-outsider");
        var own = create(request("ONBOARDING", owner));
        create(request("OFFBOARDING", other));
        for (String mine : List.of("true", "false")) {
            mvc.perform(get("/api/handovers").param("mine", mine).param("employeeUserId", other.getId().toString())
                    .with(user(AccountPrincipal.from(owner))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.canManage").value(false))
                    .andExpect(jsonPath("$.items[0].id").value(own.get("id").asLong()));
        }
        mvc.perform(get("/api/handovers").param("mine", "true")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/handovers").param("mine", "invalid").with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isBadRequest());
    }

    @Test void fullOnlineWorkflowPreservesEntireAssetResponseAndCannotBeReopened() throws Exception {
        var owner = employee("online-owner"); var own = asset("HO-004", owner); String before = assetSnapshot();
        var body = request("OFFBOARDING", owner); body.put("assetIds", List.of(own.getId())); body.put("manualItems", List.of());
        var task = create(body);
        task = action(task, "/items/0/check", admin(), Map.of("checked", true));
        task = action(task, "/items/0/confirm", owner, Map.of("offline", false));
        assertEquals("ONLINE", task.get("items").get(0).get("confirmationMethod").asString());
        task = action(task, "/complete", admin(), Map.of());
        assertEquals("COMPLETED", task.get("state").asString()); assertEquals(4, task.get("events").size());
        assertEquals(before, assetSnapshot());
        mvc.perform(post("/api/handovers/{id}/cancel", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + task.get("version").asLong() + ",\"note\":\"不能撤销完结历史\"}"))
                .andExpect(status().isConflict());
    }

    @Test void newEmployeeWithoutAccountCanUseAuditedOfflineConfirmationWithoutCreatingUserOrAsset() throws Exception {
        long userCount = users.count(); long assetCount = assets.count(); String before = assetSnapshot();
        var task = create(request("ONBOARDING", null));
        task = action(task, "/items/0/check", admin(), Map.of("checked", true));
        mvc.perform(post("/api/handovers/{id}/items/0/confirm", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + task.get("version").asLong() + ",\"offline\":true,\"note\":\" \"}"))
                .andExpect(status().isBadRequest());
        task = action(task, "/items/0/confirm", admin(), Map.of("offline", true, "note", "已核对纸质签收单TEST-001"));
        assertEquals("OFFLINE", task.get("items").get(0).get("confirmationMethod").asString());
        task = action(task, "/complete", admin(), Map.of());
        assertEquals("COMPLETED", task.get("state").asString());
        assertEquals(userCount, users.count()); assertEquals(assetCount, assets.count()); assertEquals(before, assetSnapshot());
    }

    @Test void cannotCompleteUncheckedOrUnconfirmedTaskOrImpersonateEmployee() throws Exception {
        var owner = employee("confirm-owner"); var task = create(request("ONBOARDING", owner));
        mvc.perform(post("/api/handovers/{id}/complete", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")) .andExpect(status().isConflict());
        mvc.perform(post("/api/handovers/{id}/items/0/confirm", task.get("id").asLong()).with(user(AccountPrincipal.from(owner)))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"offline\":false}")) .andExpect(status().isConflict());
        task = action(task, "/items/0/check", admin(), Map.of("checked", true));
        String version = "{\"version\":" + task.get("version").asLong();
        mvc.perform(post("/api/handovers/{id}/items/0/confirm", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(version + ",\"offline\":false}")) .andExpect(status().isForbidden());
        mvc.perform(post("/api/handovers/{id}/items/0/confirm", task.get("id").asLong()).with(user(AccountPrincipal.from(owner)))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(version + ",\"offline\":true,\"note\":\"不可代管理员\"}")) .andExpect(status().isForbidden());
        mvc.perform(post("/api/handovers/{id}/complete", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(version + "}")) .andExpect(status().isConflict());
    }

    @Test void cancelledTasksKeepHistoryAndDoNotClearOwnership() throws Exception {
        var owner = employee("cancel-owner"); asset("HO-005", owner); String before = assetSnapshot();
        var task = create(request("OFFBOARDING", owner));
        mvc.perform(post("/api/handovers/{id}/cancel", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")) .andExpect(status().isBadRequest());
        task = action(task, "/cancel", admin(), Map.of("note", "钉钉离职申请已撤销，人工登记取消"));
        assertEquals("CANCELLED", task.get("state").asString()); assertEquals(2, task.get("events").size());
        assertEquals(1, tasks.count()); assertEquals(before, assetSnapshot());
    }

    @Test void staleVersionsCannotOverwriteChecksAndConfirmationsCannotBeOverwritten() throws Exception {
        var owner = employee("version-owner"); var task = create(request("ONBOARDING", owner));
        var checked = action(task, "/items/0/check", admin(), Map.of("checked", true));
        assertTrue(checked.get("version").asLong() > task.get("version").asLong());
        mvc.perform(post("/api/handovers/{id}/items/0/check", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"checked\":false}")) .andExpect(status().isConflict());
        var confirmed = action(checked, "/items/0/confirm", owner, Map.of("offline", false));
        mvc.perform(post("/api/handovers/{id}/items/0/check", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + confirmed.get("version").asLong() + ",\"checked\":false}")) .andExpect(status().isConflict());
        mvc.perform(get("/api/handovers/{id}", task.get("id").asLong()).with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(3)).andExpect(jsonPath("$.items[0].checked").value(true));
    }

    @Test void retryingSameCreateKeyDoesNotCreateDuplicateOrOverwriteOriginal() throws Exception {
        var body = request("ONBOARDING", null); var first = create(body);
        body.put("employeeName", "重试不能覆盖原姓名"); var again = create(body);
        assertEquals(first.get("id").asLong(), again.get("id").asLong());
        assertEquals(first.get("employeeName").asString(), again.get("employeeName").asString()); assertEquals(1, tasks.count());
    }

    @Test void legacyOwnershipRequiresExplicitSelectionAndAcknowledgement() throws Exception {
        var owner = employee("legacy-owner"); var legacy = asset("HO-006", null); legacy.setAssignedTo(owner.getDisplayName()); assets.saveAndFlush(legacy);
        var body = request("OFFBOARDING", owner); body.put("assetIds", List.of(legacy.getId()));
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.put("allowLegacyAssets", true); var task = create(body);
        assertEquals(legacy.getId(), task.get("items").get(0).get("assetId").asLong()); assertNull(assets.findById(legacy.getId()).orElseThrow().getAssignedUserId());
    }

    @Test void wrongEmployeeAndAlreadyAssignedOnboardingAssetsAreRejected() throws Exception {
        var first = employee("selection-first"); var second = employee("selection-second"); var assigned = asset("HO-007", first);
        var body = request("OFFBOARDING", second); body.put("assetIds", List.of(assigned.getId()));
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.put("kind", "ONBOARDING");
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest()); assertEquals(0, tasks.count());
    }

    @Test void manualTaskCanBeExplicitlyLinkedLaterWithoutRenamingEmployeeOrChangingAsset() throws Exception {
        var owner = employee("later-account"); var task = create(request("ONBOARDING", null)); String before = assetSnapshot();
        mvc.perform(get("/api/handovers").with(user(AccountPrincipal.from(owner)))).andExpect(jsonPath("$.totalElements").value(0));
        task = action(task, "/employee", admin(), Map.of("employeeUserId", owner.getId()));
        assertEquals("未建账号的新员工", task.get("employeeName").asString());
        mvc.perform(get("/api/handovers").with(user(AccountPrincipal.from(owner)))).andExpect(jsonPath("$.totalElements").value(1));
        assertEquals(before, assetSnapshot());
        mvc.perform(post("/api/handovers/{id}/employee", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + task.get("version").asLong() + ",\"employeeUserId\":" + admin().getId() + "}"))
                .andExpect(status().isConflict());
    }

    @Test void departmentalAssetOperatorDoesNotGainGlobalHandoverManagement() throws Exception {
        var operator = employee("dept-operator"); operator.setDataScope(DataScope.DEPARTMENT); operator.setDepartments(Set.of("研发部"));
        operator.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN)); users.saveAndFlush(operator);
        create(request("ONBOARDING", null));
        mvc.perform(get("/api/handovers").with(user(AccountPrincipal.from(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(operator))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request("ONBOARDING", operator)))).andExpect(status().isForbidden());
    }

    @Test void disabledEmployeeCanBeOffboardedButIsNotReactivatedOrOnboarded() throws Exception {
        var owner = employee("disabled-offboarding"); owner.setEnabled(false); users.saveAndFlush(owner);
        var body = request("ONBOARDING", owner);
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.put("kind", "OFFBOARDING"); create(body); assertFalse(users.findById(owner.getId()).orElseThrow().isEnabled());
        mvc.perform(get("/api/handovers/employees").with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("disabled-offboarding")));
    }

    @Test void emptyAutoMatchCannotBeCompletedAsProofOfNoAssets() throws Exception {
        var owner = employee("empty-match"); var body = request("OFFBOARDING", owner);
        body.put("manualItems", List.of()); body.put("includeAssignedAssets", true);
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest()); assertEquals(0, tasks.count());
    }

    @Test void validationCsrfInvalidFiltersAndInvalidItemIndexAreEnforced() throws Exception {
        var body = request("ONBOARDING", null);
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        body.put("kind", "DELETE_ASSETS");
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/handovers?state=UNKNOWN").with(user(AccountPrincipal.from(admin())))).andExpect(status().isBadRequest());
        var task = create(request("ONBOARDING", null));
        mvc.perform(post("/api/handovers/{id}/items/-1/check", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"checked\":true}")) .andExpect(status().isNotFound());
        mvc.perform(post("/api/handovers/{id}/complete", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isBadRequest());
    }

    @Test void paginationAndKindFiltersDoNotExposeOtherEmployeesCounts() throws Exception {
        var owner = employee("page-owner"); var other = employee("page-other");
        for (int i = 0; i < 21; i++) create(request(i == 20 ? "OFFBOARDING" : "ONBOARDING", owner));
        mvc.perform(get("/api/handovers").with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/handovers?page=1").with(user(AccountPrincipal.from(owner)))).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/handovers?kind=OFFBOARDING").with(user(AccountPrincipal.from(owner)))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/handovers").with(user(AccountPrincipal.from(other)))).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void plannedOnboardingAssetDoesNotBecomeAssignedOrReservedAndClosedRecordsStayImmutable() throws Exception {
        var owner = employee("planned-owner"); var planned = asset("HO-008", null); String before = assetSnapshot();
        var body = request("ONBOARDING", owner); body.put("assetIds", List.of(planned.getId())); body.put("manualItems", List.of());
        var task = create(body);
        task = action(task, "/items/0/check", admin(), Map.of("checked", true));
        task = action(task, "/items/0/confirm", owner, Map.of("offline", false));
        task = action(task, "/complete", admin(), Map.of());
        assertEquals(before, assetSnapshot());
        assertNull(assets.findById(planned.getId()).orElseThrow().getAssignedUserId());
        assertFalse(assets.findById(planned.getId()).orElseThrow().isCheckedOut());
        mvc.perform(post("/api/handovers/{id}/items/0/check", task.get("id").asLong()).with(user(AccountPrincipal.from(admin())))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + task.get("version").asLong() + ",\"checked\":false}"))
                .andExpect(status().isConflict());
    }

    @Test void revokedManagementPermissionsTakeEffectForExistingSession() throws Exception {
        var operator = employee("revoked-manager"); operator.setDataScope(DataScope.ALL);
        operator.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN)); users.saveAndFlush(operator);
        var login = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"revoked-manager\",\"password\":\"HandoverTest123!\"}"))
                .andExpect(status().isOk()).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        var task = create(request("ONBOARDING", null));
        mvc.perform(get("/api/handovers").session(session)).andExpect(jsonPath("$.canManage").value(true));
        operator.setPermissions(Set.of(Permission.ASSET_VIEW)); users.saveAndFlush(operator);
        mvc.perform(get("/api/handovers").session(session)).andExpect(jsonPath("$.canManage").value(false))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/handovers/{id}", task.get("id").asLong()).session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/api/handovers/{id}/items/0/check", task.get("id").asLong()).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"checked\":true}")) .andExpect(status().isForbidden());
    }

    @Test void unusableOnboardingDevicesAndOversizedChecklistsAreRejected() throws Exception {
        var planned = asset("HO-009", null);
        planned.setStatus(lookups.findByTypeOrderByNameAsc(LookupType.STATUS).stream()
                .filter(value -> value.getName().contains("维修") || value.getName().contains("维护")).findFirst().orElseThrow());
        assets.saveAndFlush(planned);
        var body = request("ONBOARDING", null); body.put("assetIds", List.of(planned.getId()));
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.put("assetIds", List.of()); body.put("manualItems", Collections.nCopies(51, "超限核对项"));
        mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        assertEquals(0, tasks.count());
    }
}
