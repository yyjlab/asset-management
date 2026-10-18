package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.management.WarrantyReminder;
import com.acme.assetmanagement.workflow.*;
import jakarta.persistence.EntityManager;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:managementdb;DB_CLOSE_DELAY=-1", "app.workflow-scheduling-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ManagementApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired TaskNotificationRepository notificationRepository;
    UserAccount admin() { return users.findByUsernameIgnoreCase("admin").orElseThrow(); }
    UserAccount employee(String username) {
        var u = new UserAccount(); u.setUsername(username); u.setDisplayName("同名员工"); u.setPasswordHash("unused-test-password-hash");
        u.setAccessConfigured(true); u.setDataScope(DataScope.SELF); u.setPermissions(Set.of(Permission.ASSET_VIEW)); return users.saveAndFlush(u);
    }
    Asset asset(String tag, UserAccount owner) {
        var seed = assets.findAll().getFirst(); var a = new Asset(); a.setAssetTag(tag); a.setName("管理测试设备");
        a.setCompany(seed.getCompany()); a.setModel(seed.getModel()); a.setCategory(seed.getCategory()); a.setStatus(seed.getStatus()); a.setLocation(seed.getLocation());
        a.setManufacturerSerialNumber("PRIVATE-SN-" + tag); a.setNotes("原备注"); a.setPurchasePrice(new java.math.BigDecimal("1234.50"));
        if (owner != null) { a.setAssignedUserId(owner.getId()); a.setAssignedTo(owner.getDisplayName()); a.setCheckedOut(true); }
        return assets.saveAndFlush(a);
    }
    ResultActions post(String path, UserAccount actor, Object body) throws Exception { return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(user(AccountPrincipal.from(actor))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))); }
    ResultActions putJson(String path, UserAccount actor, Object body) throws Exception { return mvc.perform(put(path).with(user(AccountPrincipal.from(actor))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))); }
    JsonNode created(ResultActions action) throws Exception { return json.readTree(action.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()); }
    JsonNode ok(ResultActions action) throws Exception { return json.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    JsonNode getAs(String path, UserAccount actor) throws Exception { return ok(mvc.perform(get(path).with(user(AccountPrincipal.from(actor))))); }
    String snapshot() throws Exception { return mvc.perform(get("/api/assets").with(user(AccountPrincipal.from(admin())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); }
    Map<String, Object> kit(String key) { return new LinkedHashMap<>(Map.of("requestKey", key, "name", "开发岗位", "notes", "只准备，不分配", "active", true, "items", List.of(Map.of("name", "电脑", "quantity", 1), Map.of("name", "显示器", "quantity", 2)))); }
    Map<String, Object> supplierBody() { return new LinkedHashMap<>(Map.of("requestKey", UUID.randomUUID().toString(), "name", "测试售后供应商", "contact", "售后", "phone", "000-00000000", "email", "test@example.com", "notes", "测试记录", "active", true)); }
    JsonNode supplier() throws Exception { return created(post("/api/management/suppliers", admin(), supplierBody())); }
    Map<String, Object> procurement(String kind, int qty) { return new LinkedHashMap<>(Map.of("requestKey", UUID.randomUUID().toString(), "kind", kind, "title", "测试物品", "reason", "测试需求", "quantity", qty, "unit", "CONSUMABLE".equals(kind) ? "个" : "台", "estimatedUnitPrice", 10)); }
    JsonNode pAction(JsonNode detail, UserAccount actor, String action, Map<String, Object> extra) throws Exception {
        var b = new LinkedHashMap<String, Object>(extra); b.put("version", detail.path("order").path("version").asLong()); b.put("action", action); b.putIfAbsent("note", "已核对实物及数量");
        return ok(post("/api/procurements/" + detail.path("order").path("id").asLong() + "/actions", actor, b));
    }
    JsonNode ordered(String kind, int qty, UserAccount owner) throws Exception {
        var order = created(post("/api/procurements", owner, procurement(kind, qty))); order = pAction(order, admin(), "APPROVE", Map.of());
        return pAction(order, admin(), "ORDER", Map.of("supplierId", supplier().path("id").asLong(), "orderNumber", "TEST-ORDER", "expectedDate", LocalDate.now().plusDays(3).toString(), "orderedUnitPrice", 8));
    }

    @Test void readsAndWritesRequireLoginAndSensitiveManagementIsNotGrantedByMenus() throws Exception {
        var ordinary = employee("management-ordinary");
        for (String path : List.of("/api/management/workbench", "/api/management/kits", "/api/management/suppliers", "/api/management/warranties", "/api/management/reports", "/api/procurements"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String path : List.of("/api/management/kits", "/api/management/suppliers", "/api/management/warranties", "/api/management/reports"))
            mvc.perform(get(path).with(user(AccountPrincipal.from(ordinary)))).andExpect(status().isForbidden());
        post("/api/management/kits", ordinary, kit(UUID.randomUUID().toString())).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/management/kits").with(user(AccountPrincipal.from(admin()))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(kit(UUID.randomUUID().toString())))).andExpect(status().isForbidden());
    }
    @Test void kitQuantitiesExpandToIndependentItemsAndChangesDoNotChangeOldHandoversOrAssets() throws Exception {
        String before = snapshot(); String key = UUID.randomUUID().toString(); var body = kit(key); var k = created(post("/api/management/kits", admin(), body));
        assertEquals(3, k.path("manualItems").size()); assertEquals("显示器（2/2）", k.path("manualItems").get(2).asString());
        assertEquals(k.path("id"), created(post("/api/management/kits", admin(), body)).path("id"));
        var hBody = new LinkedHashMap<String, Object>(Map.of("requestKey", UUID.randomUUID().toString(), "kind", "ONBOARDING", "employeeName", "新员工", "assetIds", List.of(), "includeAssignedAssets", false, "allowLegacyAssets", false, "manualItems", List.of("电脑", "显示器（1/2）", "显示器（2/2）")));
        var handover = created(post("/api/handovers", admin(), hBody)); String oldHandover = handover.toString();
        body.put("version", k.path("version").asLong()); body.put("active", false); body.put("items", List.of(Map.of("name", "新版电脑", "quantity", 1)));
        ok(putJson("/api/management/kits/" + k.path("id").asLong(), admin(), body));
        assertEquals(oldHandover, getAs("/api/handovers/" + handover.path("id").asLong(), admin()).toString()); assertEquals(before, snapshot());
        putJson("/api/management/kits/" + k.path("id").asLong(), admin(), body).andExpect(status().isConflict());
    }
    @Test void invalidFractionalOrOversizedTemplatesAndInvalidEmailsAreRejected() throws Exception {
        var body = kit(UUID.randomUUID().toString()); body.put("items", List.of(Map.of("name", "电脑", "quantity", 1.5)));
        post("/api/management/kits", admin(), body).andExpect(status().isBadRequest());
        body.put("items", List.of(Map.of("name", "电脑", "quantity", 50), Map.of("name", "电源", "quantity", 1)));
        post("/api/management/kits", admin(), body).andExpect(status().isBadRequest());
        body.put("items", Arrays.asList((Object) null)); post("/api/management/kits", admin(), body).andExpect(status().isBadRequest());
        var s = supplierBody(); s.put("email", "not-an-email"); post("/api/management/suppliers", admin(), s).andExpect(status().isBadRequest());
        assertEquals(0L, jdbc.queryForObject("select count(*) from equipment_kits", Long.class));
    }
    @Test void supplierArchivePreservesWarrantySnapshotAndOldAssetValuesAndEnforcesVersions() throws Exception {
        var s = supplier(); var a = asset("WARRANTY-1", null); String before = snapshot(); var today = LocalDate.now();
        var body = new LinkedHashMap<String, Object>(Map.of("supplierId", s.path("id").asLong(), "purchaseDate", today.minusDays(30).toString(), "warrantyEnd", today.plusDays(5).toString(), "notes", "售后合同核对"));
        var w = ok(putJson("/api/management/warranties/" + a.getId(), admin(), body)); assertTrue(w.path("dueSoon").asBoolean()); assertEquals(before, snapshot());
        var supplierUpdate = supplierBody(); supplierUpdate.put("version", s.path("version").asLong()); supplierUpdate.put("active", false); supplierUpdate.put("name", "已停用的新名称");
        ok(putJson("/api/management/suppliers/" + s.path("id").asLong(), admin(), supplierUpdate));
        assertEquals("测试售后供应商", getAs("/api/management/warranties/" + a.getId(), admin()).path("supplierName").asString());
        putJson("/api/management/warranties/" + a.getId(), admin(), body).andExpect(status().isConflict());
        var b = asset("WARRANTY-2", null); putJson("/api/management/warranties/" + b.getId(), admin(), body).andExpect(status().isConflict());
        assertEquals(1, getAs("/api/management/warranties?filter=due", admin()).path("totalElements").asInt());
    }
    @Test void warrantyDateAndFreshPermissionsAreIndependentlyValidated() throws Exception {
        var a = asset("WARRANTY-DATE", null); var today = LocalDate.now();
        putJson("/api/management/warranties/" + a.getId(), admin(), Map.of("purchaseDate", today.toString(), "warrantyEnd", today.minusDays(1).toString())).andExpect(status().isBadRequest());
        var manager = employee("management-revoked"); manager.setDataScope(DataScope.ALL); manager.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_EDIT, Permission.ASSET_RETURN, Permission.ASSET_CHECKOUT)); users.saveAndFlush(manager); var principal = AccountPrincipal.from(manager);
        manager.setPermissions(Set.of(Permission.ASSET_VIEW)); users.saveAndFlush(manager);
        mvc.perform(get("/api/management/reports").with(user(principal))).andExpect(status().isForbidden());
        mvc.perform(get("/api/management/workbench").with(user(principal))).andExpect(status().isOk());
    }
    @Test void workbenchFiltersBeforeCountingAndDoesNotExposeSerialOrOtherEmployeeTasks() throws Exception {
        var owner = employee("todo-owner"); var other = employee("todo-other"); var a = asset("TODO", owner);
        var t = created(post("/api/operations", owner, Map.of("requestKey", UUID.randomUUID().toString(), "kind", "RETURN", "title", "本人归还", "description", "核对后归还", "assetId", a.getId())));
        var accepted = ok(post("/api/operations/" + t.path("task").path("id").asLong() + "/actions", admin(), Map.of("version", t.path("task").path("version").asLong(), "action", "APPROVE")));
        var own = getAs("/api/management/workbench", owner); var foreign = getAs("/api/management/workbench", other);
        assertEquals(1, own.path("total").asInt()); assertEquals(0, foreign.path("total").asInt()); assertFalse(own.toString().contains("PRIVATE-SN"));
        ok(post("/api/operations/" + t.path("task").path("id").asLong() + "/actions", owner, Map.of("version", accepted.path("task").path("version").asLong(), "action", "CONFIRM", "confirmed", true)));
        assertEquals(0, getAs("/api/management/workbench", owner).path("total").asInt()); assertTrue(getAs("/api/management/workbench", admin()).path("total").asInt() >= 1);
    }
    @Test void workbenchCountsAllButReturnsOnlyTenRecentRowsPerCategory() throws Exception {
        var owner = employee("todo-limited");
        for (int i = 0; i < 12; i++) created(post("/api/operations", owner, Map.of("requestKey", UUID.randomUUID().toString(), "kind", "ISSUE", "title", "待受理-" + i, "description", "测试需求")));
        var data = getAs("/api/management/workbench", admin()); var group = java.util.stream.StreamSupport.stream(data.path("groups").spliterator(), false).filter(g -> "operations".equals(g.path("section").asString())).findFirst().orElseThrow();
        assertEquals(12, group.path("total").asInt()); assertEquals(10, group.path("items").size());
    }
    @Test void historyRequiresCurrentAssetScopeAndShowsOnlyParticipatingWorkflowsForEmployees() throws Exception {
        var owner = employee("history-owner"); var other = employee("history-other"); var a = asset("HISTORY", owner);
        var task = created(post("/api/operations", owner, Map.of("requestKey", UUID.randomUUID().toString(), "kind", "REPAIR", "title", "报修", "description", "故障检查", "assetId", a.getId())));
        var history = getAs("/api/management/history/" + a.getId(), owner); assertTrue(history.toString().contains("OPERATION")); assertFalse(history.toString().contains("AUDIT"));
        mvc.perform(get("/api/management/history/" + a.getId()).with(user(AccountPrincipal.from(other)))).andExpect(status().isNotFound());
        var adminHistory = getAs("/api/management/history/" + a.getId(), admin()); assertTrue(adminHistory.path("events").size() >= 2);
        a.setAssignedUserId(other.getId()); assets.saveAndFlush(a);
        mvc.perform(get("/api/management/history/" + a.getId()).with(user(AccountPrincipal.from(owner)))).andExpect(status().isNotFound());
    }
    @Test void managementReportUsesCurrentSnapshotWithoutMutatingAssets() throws Exception {
        var owner = employee("report-owner"); asset("REPORT-OWN", owner); asset("REPORT-IDLE", null); String before = snapshot();
        var report = getAs("/api/management/reports", admin()); assertEquals(assets.count(), report.path("assets").asLong()); assertTrue(report.path("idle").asInt() >= 1);
        assertEquals(0, report.path("completedRepairCost").asInt()); assertTrue(report.path("notice").asString().contains("不作为财务报表")); assertEquals(before, snapshot());
    }
    @Test void procurementIsScopedIdempotentAndDoesNotCreateStockOnApprovalOrOrdering() throws Exception {
        var owner = employee("procure-owner"); var other = employee("procure-other"); String before = snapshot(); var body = procurement("CONSUMABLE", 5);
        var o = created(post("/api/procurements", owner, body)); assertEquals(o.path("order").path("id"), created(post("/api/procurements", owner, body)).path("order").path("id"));
        assertEquals(0, getAs("/api/procurements", other).path("totalElements").asInt());
        mvc.perform(get("/api/procurements/" + o.path("order").path("id").asLong()).with(user(AccountPrincipal.from(other)))).andExpect(status().isNotFound());
        post("/api/procurements/" + o.path("order").path("id").asLong() + "/actions", owner, Map.of("version", o.path("order").path("version").asLong(), "action", "APPROVE", "note", "不能自授管理权限")).andExpect(status().isForbidden());
        o = pAction(o, admin(), "APPROVE", Map.of()); assertEquals("APPROVED", o.path("order").path("state").asString());
        assertEquals(0L, jdbc.queryForObject("select count(*) from consumable_movements", Long.class)); assertEquals(before, snapshot());
    }
    @Test void consumableReceiptCreatesExactlyOneStockMovementAndRejectsStaleOrDuplicateCompletion() throws Exception {
        var owner = employee("procure-stock"); var order = ordered("CONSUMABLE", 5, owner);
        var item = created(post("/api/consumables", admin(), Map.of("requestKey", UUID.randomUUID().toString(), "name", "到货鼠标", "unit", "个", "lowStockThreshold", 1)));
        var fields = Map.<String, Object>of("consumableId", item.path("id").asLong(), "consumableVersion", item.path("version").asLong(), "confirmed", true);
        var done = pAction(order, admin(), "RECEIVE", fields); assertEquals("RECEIVED", done.path("order").path("state").asString()); assertEquals(5, getAs("/api/consumables/" + item.path("id").asLong(), admin()).path("quantity").asInt());
        var retry = new LinkedHashMap<>(fields); retry.put("version", done.path("order").path("version").asLong()); retry.put("action", "RECEIVE"); retry.put("note", "重复验收");
        post("/api/procurements/" + done.path("order").path("id").asLong() + "/actions", admin(), retry).andExpect(status().isConflict());
        assertEquals(1L, jdbc.queryForObject("select count(*) from consumable_movements", Long.class));
    }
    @Test void consumableReceiptRejectsUnitMismatchAndRequiresExplicitPhysicalConfirmation() throws Exception {
        var order = ordered("CONSUMABLE", 3, employee("receipt-invalid")); var item = created(post("/api/consumables", admin(), Map.of("requestKey", UUID.randomUUID().toString(), "name", "不同单位", "unit", "盒", "lowStockThreshold", 1)));
        var b = new LinkedHashMap<String, Object>(Map.of("version", order.path("order").path("version").asLong(), "action", "RECEIVE", "note", "核对数量", "consumableId", item.path("id").asLong(), "consumableVersion", item.path("version").asLong(), "confirmed", false));
        String path = "/api/procurements/" + order.path("order").path("id").asLong() + "/actions";
        post(path, admin(), b).andExpect(status().isBadRequest()); b.put("confirmed", true); post(path, admin(), b).andExpect(status().isConflict());
        assertEquals(0, getAs("/api/consumables/" + item.path("id").asLong(), admin()).path("quantity").asInt());
    }
    @Test void assetReceiptCannotConsumeHistoricalOrAssignedAssetsAndNeverOverwritesNewAssetFields() throws Exception {
        var owner = employee("procure-assets"); var old = asset("PROCURE-OLD", null); var order = ordered("ASSET", 1, owner);
        var b = new LinkedHashMap<String, Object>(Map.of("version", order.path("order").path("version").asLong(), "action", "RECEIVE", "note", "核对设备SN", "confirmed", true, "assetIds", List.of(old.getId())));
        String path = "/api/procurements/" + order.path("order").path("id").asLong() + "/actions"; post(path, admin(), b).andExpect(status().isConflict());
        var a = asset("PROCURE-NEW", null); b.put("assetIds", List.of(a.getId())); String before = snapshot();
        var received = ok(post(path, admin(), b)); assertEquals(a.getId(), received.path("assetIds").get(0).asLong()); assertEquals(before, snapshot());
        assertEquals(1L, jdbc.queryForObject("select count(*) from procurement_receipt_assets", Long.class));
    }
    @Test void invalidQuantityAndTerminalChangesAreRejectedAndCancellationDoesNotDeleteHistory() throws Exception {
        var owner = employee("procure-validation"); var body = procurement("ASSET", 1); body.put("quantity", 1.5); post("/api/procurements", owner, body).andExpect(status().isBadRequest());
        body.put("quantity", 101); post("/api/procurements", owner, body).andExpect(status().isBadRequest());
        body.put("quantity", 1); var order = created(post("/api/procurements", owner, body)); var cancelled = pAction(order, owner, "CANCEL", Map.of("note", "不再需要"));
        post("/api/procurements/" + cancelled.path("order").path("id").asLong() + "/actions", admin(), Map.of("version", cancelled.path("order").path("version").asLong(), "action", "APPROVE", "note", "终态不可改")).andExpect(status().isConflict());
        assertEquals(2, getAs("/api/procurements/" + cancelled.path("order").path("id").asLong(), owner).path("events").size());
    }
    @Test void stockVersionConflictDoesNotPartiallyReceiveAnOrder() throws Exception {
        var order = ordered("CONSUMABLE", 2, employee("receipt-stale"));
        var item = created(post("/api/consumables", admin(), Map.of("requestKey", UUID.randomUUID().toString(), "name", "验收版本测试", "unit", "个", "lowStockThreshold", 0)));
        ok(post("/api/consumables/" + item.path("id").asLong() + "/movements", admin(), Map.of("requestKey", UUID.randomUUID().toString(), "version", item.path("version").asLong(), "kind", "IN", "quantity", 1, "note", "其他入库")));
        post("/api/procurements/" + order.path("order").path("id").asLong() + "/actions", admin(), Map.of("version", order.path("order").path("version").asLong(), "action", "RECEIVE", "note", "旧库存版本", "confirmed", true, "consumableId", item.path("id").asLong(), "consumableVersion", item.path("version").asLong())).andExpect(status().isConflict());
        assertEquals("ORDERED", getAs("/api/procurements/" + order.path("order").path("id").asLong(), admin()).path("order").path("state").asString());
        assertEquals(1, getAs("/api/consumables/" + item.path("id").asLong(), admin()).path("quantity").asInt());
        assertEquals(1L, jdbc.queryForObject("select count(*) from consumable_movements", Long.class));
    }
    @Test void oneDeviceCannotBeCountedAsTwoProcurementReceipts() throws Exception {
        var owner = employee("receipt-unique"); var first = ordered("ASSET", 1, owner); var second = ordered("ASSET", 1, owner);
        var a = asset("RECEIPT-ONCE", null); pAction(first, admin(), "RECEIVE", Map.of("assetIds", List.of(a.getId()), "confirmed", true));
        post("/api/procurements/" + second.path("order").path("id").asLong() + "/actions", admin(), Map.of("version", second.path("order").path("version").asLong(), "action", "RECEIVE", "note", "不能重复关联到货", "assetIds", List.of(a.getId()), "confirmed", true)).andExpect(status().isConflict());
    }
    @Test void warrantyDigestIsDisabledByDefaultDeduplicatesDailyAndExcludesOrphanAndOutOfRangeRows() throws Exception {
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (int i = 0; i < 12; i++) {
            var a = asset("DUE-" + i, null);
            ok(putJson("/api/management/warranties/" + a.getId(), admin(), Map.of("warrantyEnd", today.plusDays(i == 11 ? 30 : i).toString(), "notes", "PRIVATE-NOTE")));
        }
        for (int days : List.of(-1, 31)) { var a = asset("OUTSIDE-" + days, null); ok(putJson("/api/management/warranties/" + a.getId(), admin(), Map.of("warrantyEnd", today.plusDays(days).toString()))); }
        var orphan = asset("ORPHAN-WARRANTY", null); ok(putJson("/api/management/warranties/" + orphan.getId(), admin(), Map.of("warrantyEnd", today.plusDays(2).toString())));
        jdbc.update("update asset_warranties set asset_id = ? where asset_id = ?", Long.MAX_VALUE, orphan.getId());
        var disabled = new WorkflowNotificationProperties(false, "", "");
        new WarrantyReminder(em, new TaskNotifications(notificationRepository, disabled), disabled).daily();
        assertEquals(0, notificationRepository.count());
        var enabled = new WorkflowNotificationProperties(true, "", "");
        var reminder = new WarrantyReminder(em, new TaskNotifications(notificationRepository, enabled), enabled); reminder.daily(); reminder.daily();
        assertEquals(1, notificationRepository.count());
        var content = jdbc.queryForObject("select content from asset_task_notifications where category = 'WARRANTY'", String.class);
        assertNotNull(content); assertTrue(content.contains("未来30天保修到期：12台")); assertFalse(content.contains("DUE-11："));
        for (String excluded : List.of("OUTSIDE-", "ORPHAN-", "PRIVATE-SN", "PRIVATE-NOTE", "1234.50")) assertFalse(content.contains(excluded));
    }
}
