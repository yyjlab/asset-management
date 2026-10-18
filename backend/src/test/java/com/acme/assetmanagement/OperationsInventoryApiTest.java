package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:operationsinventorydb;DB_CLOSE_DELAY=-1", "app.workflow-scheduling-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OperationsInventoryApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Autowired LookupRepository lookups;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    private UserAccount admin() { return users.findByUsernameIgnoreCase("admin").orElseThrow(); }
    private UserAccount employee(String username) {
        var account = new UserAccount(); account.setUsername(username); account.setDisplayName("同名员工");
        account.setPasswordHash(encoder.encode("WorkflowTest123!")); account.setAccessConfigured(true);
        account.setPermissions(Set.of(Permission.ASSET_VIEW)); account.setDataScope(DataScope.SELF);
        return users.saveAndFlush(account);
    }
    private LookupValue lookupStatus(String name) {
        return lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, name)
                .or(() -> Set.of("当前可用", "已经领出").contains(name)
                        ? lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "当前可用".equals(name) ? "可领用" : "在用") : Optional.empty())
                .orElseThrow();
    }
    private Asset asset(String tag, UserAccount owner) {
        var seed = assets.findAll().getFirst(); var a = new Asset();
        a.setAssetTag(tag); a.setName("流程测试电脑"); a.setCompany(seed.getCompany()); a.setModel(seed.getModel());
        a.setCategory(seed.getCategory()); a.setLocation(seed.getLocation()); a.setOwnershipDepartment("流程测试部");
        a.setStatus(lookupStatus(owner == null ? "当前可用" : "已经领出")); a.setCpu("历史CPU"); a.setMemory("32GB"); a.setStorage("1TB");
        a.setManufacturerSerialNumber("SN-" + tag); a.setNotes("历史备注不可覆盖");
        a.setPurchasePrice(new BigDecimal("6234.56")); a.setCurrentValue(new BigDecimal("4321.00"));
        a.setImageUrl("/api/public/asset-images/historical-workflow.png");
        a.getImageUrls().add("/api/public/asset-images/historical-workflow-2.png");
        a.getAccessories().add(new AssetAccessory("电源", "原装", 1));
        if (owner != null) { a.setCheckedOut(true); a.setAssignedUserId(owner.getId()); a.setAssignedTo(owner.getDisplayName()); }
        return assets.saveAndFlush(a);
    }
    private void historicalFieldsUnchanged(Asset a) {
        assertEquals(new BigDecimal("6234.56"), a.getPurchasePrice()); assertEquals(new BigDecimal("4321.00"), a.getCurrentValue());
        assertEquals("历史备注不可覆盖", a.getNotes()); assertEquals("SN-" + a.getAssetTag(), a.getManufacturerSerialNumber());
        assertEquals("/api/public/asset-images/historical-workflow.png", a.getImageUrl());
        assertEquals(List.of("/api/public/asset-images/historical-workflow-2.png"), a.getImageUrls());
        assertEquals(1, a.getAccessories().size()); assertEquals("电源", a.getAccessories().getFirst().getName());
    }
    private Map<String, Object> operation(String kind, Asset a) {
        var body = new LinkedHashMap<String, Object>(); body.put("requestKey", UUID.randomUUID().toString());
        body.put("kind", kind); body.put("title", "设备流程"); body.put("description", "核对实物后处理");
        if (a != null) body.put("assetId", a.getId()); return body;
    }
    private ResultActions postAs(String path, UserAccount actor, Object body) throws Exception {
        return mvc.perform(post(path).with(user(AccountPrincipal.from(actor))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    private JsonNode response(ResultActions action) throws Exception {
        return json.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode createOperation(UserAccount actor, Map<String, Object> body) throws Exception {
        return json.readTree(postAs("/api/operations", actor, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private Map<String, Object> actionBody(JsonNode detail, String action, Map<String, Object> extra) {
        var body = new LinkedHashMap<String, Object>(extra); body.put("version", detail.get("task").get("version").asLong()); body.put("action", action); return body;
    }
    private ResultActions operationAction(JsonNode detail, UserAccount actor, String action, Map<String, Object> extra) throws Exception {
        return postAs("/api/operations/" + detail.get("task").get("id").asLong() + "/actions", actor, actionBody(detail, action, extra));
    }
    private JsonNode act(JsonNode detail, UserAccount actor, String action, Map<String, Object> extra) throws Exception {
        return response(operationAction(detail, actor, action, extra));
    }
    private String allAssets() throws Exception {
        return mvc.perform(get("/api/assets").with(user(AccountPrincipal.from(admin())))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
    private Map<String, Object> inventory() {
        return new LinkedHashMap<>(Map.of("requestKey", UUID.randomUUID().toString(), "title", "部门盘点", "department", "流程测试部", "dueDate", LocalDate.now().plusDays(2).toString()));
    }
    private JsonNode createInventory(Map<String, Object> body) throws Exception {
        return json.readTree(postAs("/api/inventories", admin(), body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private ResultActions inventoryAction(JsonNode detail, UserAccount actor, String action, Map<String, Object> extra) throws Exception {
        var body = new LinkedHashMap<String, Object>(extra); body.put("version", detail.get("batch").get("version").asLong()); body.put("action", action);
        return postAs("/api/inventories/" + detail.get("batch").get("id").asLong() + "/actions", actor, body);
    }
    private JsonNode inventoryGet(JsonNode detail, UserAccount actor) throws Exception {
        return response(mvc.perform(get("/api/inventories/{id}", detail.get("batch").get("id").asLong()).with(user(AccountPrincipal.from(actor)))));
    }
    private int indexOf(JsonNode detail, Long assetId) {
        for (var item : detail.get("items")) if (item.get("assetId").asLong() == assetId) return item.get("index").asInt();
        throw new AssertionError("Asset not in inventory snapshot");
    }

    @Test void acceptedWorkflowsReserveDeviceUntilCancellationWithoutChangingTheLedger() throws Exception {
        var owner = employee("reservation-owner"); var other = employee("reservation-other"); var available = asset("OP-RESERVE", null);
        var first = createOperation(owner, operation("ISSUE", null)); var second = createOperation(other, operation("ISSUE", null));
        String before = allAssets(); var choice = Map.<String, Object>of("assetId", available.getId(), "assetVersion", available.getVersion());
        first = act(first, admin(), "APPROVE", choice);
        operationAction(second, admin(), "APPROVE", choice).andExpect(status().isConflict()); assertEquals(before, allAssets());
        act(first, admin(), "CANCEL", Map.of("note", "收回本次分配，设备未发出"));
        second = act(second, admin(), "APPROVE", choice); assertEquals("WAITING_CONFIRM", second.get("task").get("state").asString());
        assertEquals(before, allAssets());
    }
    @Test void completingAWorkflowReleasesItsReservationForTheNextLegitimateOperation() throws Exception {
        var owner = employee("reservation-complete"); var available = asset("OP-RESERVE-DONE", null);
        var issue = createOperation(owner, operation("ISSUE", null));
        issue = act(issue, admin(), "APPROVE", Map.of("assetId", available.getId(), "assetVersion", available.getVersion()));
        issue = act(issue, owner, "CONFIRM", Map.of("confirmed", true));
        act(issue, admin(), "COMPLETE", Map.of("assetVersion", available.getVersion(), "confirmed", true, "note", "已验收配发"));
        var returning = createOperation(owner, operation("RETURN", available));
        returning = act(returning, admin(), "APPROVE", Map.of());
        assertEquals("WAITING_CONFIRM", returning.get("task").get("state").asString()); assertEquals(owner.getId(), available.getAssignedUserId());
    }

    @Test void endpointsRequireAuthenticationCsrfAndRealOwnerIdsNotDisplayNames() throws Exception {
        var owner = employee("op-owner"); var outsider = employee("op-outsider"); var own = asset("OP-SEC", owner);
        var body = operation("RETURN", own); String before = allAssets();
        mvc.perform(get("/api/operations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/inventories")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/operations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/inventories").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(inventory()))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/operations").with(user(AccountPrincipal.from(owner))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        postAs("/api/operations", outsider, body).andExpect(status().isNotFound());
        var spoofed = operation("ISSUE", null); spoofed.put("ownerId", owner.getId());
        postAs("/api/operations", outsider, spoofed).andExpect(status().isForbidden());
        postAs("/api/inventories", owner, inventory()).andExpect(status().isForbidden());
        mvc.perform(post("/api/inventories").with(user(AccountPrincipal.from(admin()))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(inventory()))).andExpect(status().isForbidden());
        var task = createOperation(owner, body);
        mvc.perform(get("/api/operations/{id}", task.get("task").get("id").asLong()).with(user(AccountPrincipal.from(outsider)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/operations").param("mine", "false").param("ownerId", owner.getId().toString()).with(user(AccountPrincipal.from(outsider))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        operationAction(task, owner, "APPROVE", Map.of()).andExpect(status().isForbidden());
        assertEquals(before, allAssets());
    }

    @Test void revokedManagerPermissionsAndUnavailableEquipmentCannotBypassFinalChecks() throws Exception {
        var owner = employee("fresh-permission-owner"); var manager = employee("fresh-permission-manager");
        manager.setDataScope(DataScope.ALL); manager.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_EDIT, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN));
        users.saveAndFlush(manager); var stalePrincipal = AccountPrincipal.from(manager);
        var unavailable = asset("OP-UNAVAILABLE", owner); var task = createOperation(owner, operation("ISSUE", null));
        operationAction(task, manager, "APPROVE", Map.of("assetId", unavailable.getId(), "assetVersion", unavailable.getVersion())).andExpect(status().isConflict());
        var available = asset("OP-PERMISSION", null); task = act(task, manager, "APPROVE", Map.of("assetId", available.getId(), "assetVersion", available.getVersion()));
        task = act(task, owner, "CONFIRM", Map.of("confirmed", true)); String before = allAssets();
        manager.setPermissions(Set.of(Permission.ASSET_VIEW)); users.saveAndFlush(manager);
        mvc.perform(post("/api/operations/{id}/actions", task.get("task").get("id").asLong()).with(user(stalePrincipal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(actionBody(task, "COMPLETE", Map.of("note", "旧会话不能保留已撤销的权限", "confirmed", true, "assetVersion", available.getVersion())))))
                .andExpect(status().isNotFound());
        assertEquals(before, allAssets()); assertNull(available.getAssignedUserId());
    }

    @Test void issueIsIdempotentAndWritesOnlyAfterEmployeeConfirmationAndManagerFinalCompletion() throws Exception {
        var owner = employee("issue-owner"); var available = asset("OP-ISSUE", null); var body = operation("ISSUE", null);
        String before = allAssets(); var task = createOperation(owner, body); body.put("title", "重试不能改标题");
        var replay = createOperation(owner, body); assertEquals(task, replay);
        operationAction(task, admin(), "COMPLETE", Map.of("note", "不能跳过", "confirmed", true, "assetVersion", available.getVersion())).andExpect(status().isConflict());
        var approved = act(task, admin(), "APPROVE", Map.of("assetId", available.getId(), "assetVersion", available.getVersion()));
        operationAction(task, admin(), "CANCEL", Map.of("note", "旧版本不可覆盖")).andExpect(status().isConflict());
        operationAction(approved, admin(), "CONFIRM", Map.of("confirmed", true)).andExpect(status().isForbidden());
        operationAction(approved, owner, "CONFIRM", Map.of("confirmed", false)).andExpect(status().isBadRequest());
        var confirmed = act(approved, owner, "CONFIRM", Map.of("confirmed", true));
        assertEquals(before, allAssets());
        var completed = act(confirmed, admin(), "COMPLETE", Map.of("note", "核对后发放", "confirmed", true, "assetVersion", available.getVersion()));
        assertEquals("COMPLETED", completed.get("task").get("state").asString());
        assertEquals(owner.getId(), available.getAssignedUserId()); assertTrue(available.isCheckedOut()); assertEquals(lookupStatus("已经领出").getName(), available.getStatus().getName());
        historicalFieldsUnchanged(available);
        operationAction(completed, admin(), "CANCEL", Map.of("note", "不能改历史")).andExpect(status().isConflict());
    }

    @Test void returnPreservesOwnershipUntilFinalAcceptanceAndDoesNotEraseHistoricalData() throws Exception {
        var owner = employee("return-owner"); var own = asset("OP-RETURN", owner); String before = allAssets();
        var task = createOperation(owner, operation("RETURN", own));
        task = act(task, admin(), "APPROVE", Map.of()); task = act(task, owner, "CONFIRM", Map.of("confirmed", true));
        assertEquals(before, allAssets());
        operationAction(task, owner, "COMPLETE", Map.of("note", "不可自行验收", "confirmed", true, "assetVersion", own.getVersion())).andExpect(status().isForbidden());
        act(task, admin(), "COMPLETE", Map.of("note", "实物已收回", "confirmed", true, "assetVersion", own.getVersion()));
        assertFalse(own.isCheckedOut()); assertNull(own.getAssignedUserId()); assertNull(own.getAssignedTo()); assertEquals(lookupStatus("当前可用").getName(), own.getStatus().getName());
        historicalFieldsUnchanged(own);
    }

    @Test void transferRequiresNamedReceiverNotOwnerOrAdministratorAndStaysScoped() throws Exception {
        var owner = employee("transfer-owner"); var receiver = employee("transfer-receiver"); var outsider = employee("transfer-outsider"); var own = asset("OP-TRANSFER", owner);
        var body = operation("TRANSFER", own); body.put("targetUsername", receiver.getUsername()); String before = allAssets();
        var task = createOperation(owner, body);
        mvc.perform(get("/api/operations").with(user(AccountPrincipal.from(receiver)))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/operations/{id}", task.get("task").get("id").asLong()).with(user(AccountPrincipal.from(outsider)))).andExpect(status().isNotFound());
        task = act(task, admin(), "APPROVE", Map.of());
        operationAction(task, owner, "CONFIRM", Map.of("confirmed", true)).andExpect(status().isForbidden());
        operationAction(task, admin(), "CONFIRM", Map.of("confirmed", true)).andExpect(status().isForbidden());
        task = act(task, receiver, "CONFIRM", Map.of("confirmed", true)); assertEquals(before, allAssets());
        act(task, admin(), "COMPLETE", Map.of("note", "双方实物交接核对完成", "confirmed", true, "assetVersion", own.getVersion()));
        assertEquals(receiver.getId(), own.getAssignedUserId()); assertTrue(own.isCheckedOut()); historicalFieldsUnchanged(own);
    }

    @Test void returnCompletionDoesNotMakeScrappedOrRepairEquipmentAvailable() throws Exception {
        var owner = employee("unavailable-return-owner");
        int index = 0;
        for (String name : List.of("维修中", "已报废")) {
            var own = asset("OP-UNAVAILABLE-RETURN-" + index++, owner);
            own.setStatus(lookupStatus(name)); assets.saveAndFlush(own);
            var task = createOperation(owner, operation("RETURN", own));
            task = act(task, admin(), "APPROVE", Map.of());
            task = act(task, owner, "CONFIRM", Map.of("confirmed", true));
            act(task, admin(), "COMPLETE", Map.of("note", "只收回实物，保留维修或报废结果", "confirmed", true, "assetVersion", own.getVersion()));
            assertEquals(name, own.getStatus().getName());
            assertFalse(own.isCheckedOut()); assertNull(own.getAssignedUserId());
            historicalFieldsUnchanged(own);
        }
    }

    @Test void transferringCustodyDoesNotUndoRepairOrScrapResults() throws Exception {
        var owner = employee("unavailable-transfer-owner"); var receiver = employee("unavailable-transfer-receiver");
        int index = 0;
        for (String name : List.of("维修中", "已报废")) {
            var own = asset("OP-UNAVAILABLE-TRANSFER-" + index++, owner);
            own.setStatus(lookupStatus(name)); assets.saveAndFlush(own);
            var body = operation("TRANSFER", own); body.put("targetUsername", receiver.getUsername());
            var task = createOperation(owner, body); task = act(task, admin(), "APPROVE", Map.of());
            task = act(task, receiver, "CONFIRM", Map.of("confirmed", true));
            act(task, admin(), "COMPLETE", Map.of("note", "移交实物保管，保留维修或报废结果", "confirmed", true, "assetVersion", own.getVersion()));
            assertEquals(name, own.getStatus().getName()); assertTrue(own.isCheckedOut());
            assertEquals(receiver.getId(), own.getAssignedUserId()); historicalFieldsUnchanged(own);
        }
    }

    @Test void concurrentAssetChangeCannotBeOverwrittenByAConfirmedOperation() throws Exception {
        var owner = employee("stale-owner"); var own = asset("OP-STALE", owner);
        var task = createOperation(owner, operation("RETURN", own)); task = act(task, admin(), "APPROVE", Map.of());
        task = act(task, owner, "CONFIRM", Map.of("confirmed", true));
        own.setNotes("管理员刚刚修改的真实备注"); assets.saveAndFlush(own); String afterChange = allAssets();
        operationAction(task, admin(), "COMPLETE", Map.of("note", "不得覆盖新台账", "confirmed", true, "assetVersion", own.getVersion())).andExpect(status().isConflict());
        assertEquals(afterChange, allAssets()); assertEquals(owner.getId(), own.getAssignedUserId());
    }

    @Test void correctionAcceptsOnlyWhitelistedFieldsAndRequiresExplicitFinalWrite() throws Exception {
        var owner = employee("correction-owner"); var own = asset("OP-CORRECTION", owner);
        var invalid = operation("CORRECTION", own); invalid.put("proposed", Map.of("assignedUserId", "123"));
        postAs("/api/operations", owner, invalid).andExpect(status().isBadRequest());
        invalid.put("proposed", Map.of("purchasePrice", "0")); postAs("/api/operations", owner, invalid).andExpect(status().isBadRequest());
        var body = operation("CORRECTION", own); body.put("proposed", Map.of("cpu", " 新CPU ", "ownershipDepartment", "核实后的部门"));
        String before = allAssets(); var task = createOperation(owner, body); task = act(task, admin(), "APPROVE", Map.of());
        task = act(task, owner, "CONFIRM", Map.of("confirmed", true)); assertEquals(before, allAssets());
        operationAction(task, admin(), "COMPLETE", Map.of("note", "必须单独确认写台账", "confirmed", false, "assetVersion", own.getVersion())).andExpect(status().isBadRequest());
        act(task, admin(), "COMPLETE", Map.of("note", "已核实纠错字段", "confirmed", true, "assetVersion", own.getVersion()));
        assertEquals("新CPU", own.getCpu()); assertEquals("核实后的部门", own.getOwnershipDepartment());
        assertEquals("32GB", own.getMemory()); assertEquals(owner.getId(), own.getAssignedUserId()); historicalFieldsUnchanged(own);
    }

    @Test void repairCostsAndOutcomesAreValidatedAndScrappingNeedsOwnerConfirmation() throws Exception {
        var owner = employee("repair-owner"); var own = asset("OP-REPAIR", owner); String before = allAssets();
        var task = createOperation(owner, operation("REPAIR", own)); task = act(task, admin(), "APPROVE", Map.of());
        assertEquals("PROCESSING", task.get("task").get("state").asString());
        operationAction(task, admin(), "RESOLVE", Map.of("note", "不可负费用", "outcome", "FIXED", "repairCost", -1)).andExpect(status().isBadRequest());
        operationAction(task, admin(), "RESOLVE", Map.of("note", "不可伪造结果", "outcome", "OTHER", "repairCost", 0)).andExpect(status().isBadRequest());
        operationAction(task, admin(), "RESOLVE", Map.of("note", "结果费用缺失")).andExpect(status().isBadRequest());
        task = act(task, admin(), "PROGRESS", Map.of("note", "已送检"));
        task = act(task, admin(), "RESOLVE", Map.of("note", "核实无法修复", "outcome", "SCRAP", "repairCost", new BigDecimal("19.95")));
        assertEquals(new BigDecimal("19.95"), task.get("repairCost").decimalValue()); assertEquals(before, allAssets());
        operationAction(task, admin(), "CONFIRM", Map.of("confirmed", true)).andExpect(status().isForbidden());
        task = act(task, owner, "CONFIRM", Map.of("confirmed", true));
        act(task, admin(), "COMPLETE", Map.of("note", "确认报废结果写入状态", "confirmed", true, "assetVersion", own.getVersion()));
        assertEquals("已报废", own.getStatus().getName()); historicalFieldsUnchanged(own);
    }

    @Test void fixedRepairAndRejectedOrCancelledRequestsKeepHistoryWithoutUnapprovedAssetWrites() throws Exception {
        var owner = employee("fixed-owner"); var own = asset("OP-FIXED", owner);
        var rejected = createOperation(owner, operation("RETURN", own)); String before = allAssets();
        operationAction(rejected, admin(), "REJECT", Map.of("note", " ")).andExpect(status().isBadRequest());
        rejected = act(rejected, admin(), "REJECT", Map.of("note", "重复申请"));
        operationAction(rejected, admin(), "APPROVE", Map.of()).andExpect(status().isConflict());
        var cancelled = createOperation(owner, operation("RETURN", own)); cancelled = act(cancelled, owner, "CANCEL", Map.of("note", "暂不归还"));
        operationAction(cancelled, admin(), "APPROVE", Map.of()).andExpect(status().isConflict()); assertEquals(before, allAssets());
        var repair = createOperation(owner, operation("REPAIR", own)); repair = act(repair, admin(), "APPROVE", Map.of());
        repair = act(repair, admin(), "RESOLVE", Map.of("note", "免费修复", "outcome", "FIXED", "repairCost", 0));
        repair = act(repair, owner, "CONFIRM", Map.of("confirmed", true));
        repair = act(repair, admin(), "COMPLETE", Map.of("note", "运行正常", "confirmed", true, "assetVersion", own.getVersion()));
        assertEquals("FIXED", repair.get("outcome").asString()); assertEquals(lookupStatus("已经领出").getName(), own.getStatus().getName()); assertEquals(owner.getId(), own.getAssignedUserId());
        assertEquals(5, repair.get("events").size()); historicalFieldsUnchanged(own);
    }

    @Test void inventoryIsAnIdempotentDepartmentSnapshotWithPrivateEmployeeItemsAndNoLedgerWrites() throws Exception {
        var owner = employee("inventory-owner"); var sameName = employee("inventory-same-name"); var outsider = employee("inventory-outsider");
        var own = asset("INV-A", owner); asset("INV-B", sameName); asset("INV-C", null);
        var outside = asset("INV-D", owner); outside.setOwnershipDepartment("其他部门"); assets.saveAndFlush(outside);
        var body = inventory(); String before = allAssets(); var batch = createInventory(body); body.put("title", "重试不可替换快照");
        assertEquals(batch, createInventory(body)); assertEquals(3, batch.get("items").size());
        var personal = inventoryGet(batch, owner); assertFalse(personal.get("canManage").asBoolean());
        assertEquals(1, personal.get("items").size()); assertEquals(own.getId(), personal.get("items").get(0).get("assetId").asLong());
        assertEquals(1, personal.get("batch").get("total").asInt()); assertEquals(0, personal.get("events").size());
        mvc.perform(get("/api/inventories").with(user(AccountPrincipal.from(outsider)))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/inventories/{id}", batch.get("batch").get("id").asLong()).with(user(AccountPrincipal.from(outsider)))).andExpect(status().isNotFound());
        assertEquals(before, allAssets());
    }

    @Test void inventoryCannotBeSignedByAdminAndRequiresReasonedResolutionBeforeClosure() throws Exception {
        var owner = employee("inventory-sign-owner"); var other = employee("inventory-sign-other"); var own = asset("INV-SIGN-A", owner); var theirs = asset("INV-SIGN-B", other);
        var batch = createInventory(inventory()); String before = allAssets(); int ownIndex = indexOf(batch, own.getId()); int theirIndex = indexOf(batch, theirs.getId());
        inventoryAction(batch, admin(), "CONFIRM", Map.of("index", ownIndex, "result", "PRESENT")).andExpect(status().isForbidden());
        inventoryAction(batch, owner, "CONFIRM", Map.of("index", theirIndex, "result", "PRESENT")).andExpect(status().isForbidden());
        inventoryAction(batch, owner, "CONFIRM", Map.of("index", ownIndex, "result", "MISSING")).andExpect(status().isBadRequest());
        var first = response(inventoryAction(batch, owner, "CONFIRM", Map.of("index", ownIndex, "result", "MISSING", "note", "本人尚未找到设备")));
        inventoryAction(batch, admin(), "CLOSE", Map.of("note", "旧版本")).andExpect(status().isConflict());
        inventoryAction(first, admin(), "CLOSE", Map.of("note", "差异尚未处理")).andExpect(status().isConflict());
        inventoryAction(first, admin(), "RESOLVE", Map.of("index", ownIndex, "note", " ")).andExpect(status().isBadRequest());
        var resolved = response(inventoryAction(first, admin(), "RESOLVE", Map.of("index", ownIndex, "note", "已人工核实借用位置，仅记录处理不改台账")));
        inventoryAction(resolved, owner, "CONFIRM", Map.of("index", ownIndex, "result", "PRESENT")).andExpect(status().isConflict());
        resolved = response(inventoryAction(resolved, other, "CONFIRM", Map.of("index", theirIndex, "result", "PRESENT")));
        resolved = response(inventoryAction(resolved, admin(), "RESOLVE", Map.of("index", theirIndex, "note", "实物核对一致")));
        var closed = response(inventoryAction(resolved, admin(), "CLOSE", Map.of("note", "全部逐项核实完成")));
        assertEquals("COMPLETED", closed.get("batch").get("state").asString()); assertEquals(before, allAssets());
        inventoryAction(closed, admin(), "CANCEL", Map.of("note", "不能改关闭历史")).andExpect(status().isConflict());
    }

    @Test void inventoryShowsLedgerChangesButKeepsOriginalSnapshotAndDoesNotRepairItAutomatically() throws Exception {
        var owner = employee("inventory-change-owner"); var own = asset("INV-CHANGE", owner); var batch = createInventory(inventory());
        assertFalse(batch.get("items").get(0).get("ledgerChanged").asBoolean()); String originalName = own.getName(); long originalVersion = own.getVersion();
        own.setName("后续台账真实新名称"); own.setNotes("后续台账真实新备注"); assets.saveAndFlush(own);
        String afterChange = allAssets(); var loaded = inventoryGet(batch, owner);
        assertTrue(loaded.get("items").get(0).get("ledgerChanged").asBoolean()); assertEquals(originalName, loaded.get("items").get(0).get("name").asString());
        assertEquals(originalVersion, loaded.get("items").get(0).get("assetVersion").asLong());
        var confirmed = response(inventoryAction(loaded, owner, "CONFIRM", Map.of("index", 0, "result", "MISMATCH", "note", "已发现名称变化")));
        var resolved = response(inventoryAction(confirmed, admin(), "RESOLVE", Map.of("index", 0, "note", "记录差异，不覆盖台账新修改")));
        response(inventoryAction(resolved, admin(), "CLOSE", Map.of("note", "完成核对"))); assertEquals(afterChange, allAssets());
    }

    @Test void inventoryRejectsInvalidDatesIndicesAndRepeatConfirmationAndCanCancelWithoutDeletion() throws Exception {
        var owner = employee("inventory-validation-owner"); asset("INV-VALID", owner); var body = inventory();
        body.put("dueDate", LocalDate.now().minusDays(1).toString()); postAs("/api/inventories", admin(), body).andExpect(status().isBadRequest());
        var batch = createInventory(inventory()); String before = allAssets();
        inventoryAction(batch, owner, "CONFIRM", Map.of("index", -1, "result", "PRESENT")).andExpect(status().isBadRequest());
        inventoryAction(batch, owner, "CONFIRM", Map.of("index", 2, "result", "PRESENT")).andExpect(status().isBadRequest());
        inventoryAction(batch, owner, "CONFIRM", Map.of("index", 0, "result", "UNKNOWN")).andExpect(status().isBadRequest());
        var confirmed = response(inventoryAction(batch, owner, "CONFIRM", Map.of("index", 0, "result", "PRESENT")));
        inventoryAction(confirmed, owner, "CONFIRM", Map.of("index", 0, "result", "PRESENT")).andExpect(status().isConflict());
        inventoryAction(confirmed, owner, "CANCEL", Map.of("note", "员工不能取消部门盘点")).andExpect(status().isForbidden());
        var cancelled = response(inventoryAction(confirmed, admin(), "CANCEL", Map.of("note", "部门范围有误，留档取消")));
        assertEquals("CANCELLED", cancelled.get("batch").get("state").asString()); assertTrue(cancelled.get("events").size() >= 3);
        assertEquals(before, allAssets());
    }
}
