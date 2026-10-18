package com.acme.assetmanagement.consumable;

import com.acme.assetmanagement.asset.AssetRepository;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:consumabledb;DB_CLOSE_DELAY=-1", "app.workflow-scheduling-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConsumableApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Autowired ConsumableItemRepository items;
    @Autowired ConsumableMovementRepository movements;
    @Autowired ConsumableSettingChangeRepository changes;
    @Autowired ConsumableService service;
    private UserAccount admin() { return users.findByUsernameIgnoreCase("admin").orElseThrow(); }
    private UserAccount employee(String name) {
        var account = new UserAccount(); account.setUsername(name); account.setDisplayName("同名员工");
        account.setPasswordHash(admin().getPasswordHash()); account.setAccessConfigured(true);
        account.setDataScope(DataScope.SELF); account.setPermissions(Set.of());
        return users.saveAndFlush(account);
    }
    private Map<String, Object> createBody(String name) {
        var request = new LinkedHashMap<String, Object>(); request.put("requestKey", UUID.randomUUID().toString());
        request.put("name", name); request.put("unit", "个"); request.put("lowStockThreshold", 2); request.put("notes", "办公耗材"); return request;
    }
    private JsonNode create(String name) throws Exception { return postJson("/api/consumables", createBody(name), admin(), 201); }
    private Map<String, Object> moveBody(JsonNode item, String kind, long quantity, String recipient) {
        var request = new LinkedHashMap<String, Object>(); request.put("requestKey", UUID.randomUUID().toString());
        request.put("version", item.get("version").asLong()); request.put("kind", kind); request.put("quantity", quantity);
        request.put("recipientUsername", recipient); request.put("note", "实物已核对"); return request;
    }
    private JsonNode move(JsonNode item, String kind, long quantity, String recipient) throws Exception {
        return postJson("/api/consumables/" + item.get("id").asLong() + "/movements", moveBody(item, kind, quantity, recipient), admin(), 200);
    }
    private JsonNode postJson(String path, Object body, UserAccount actor, int expected) throws Exception {
        return json.readTree(mvc.perform(post(path).with(user(AccountPrincipal.from(actor))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private Map<String, Object> settingsBody(JsonNode item) {
        var request = createBody(item.get("name").asString()); request.put("version", item.get("version").asLong());
        request.put("unit", item.get("unit").asString()); request.put("lowStockThreshold", item.get("lowStockThreshold").asLong());
        return request;
    }

    @Test void authenticationCsrfAndManagerPermissionsProtectWrites() throws Exception {
        mvc.perform(get("/api/consumables")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/consumables/movements")).andExpect(status().isUnauthorized());
        var body = createBody("键盘");
        mvc.perform(post("/api/consumables").with(user(AccountPrincipal.from(admin())))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        var employee = employee("consumable-normal");
        postJson("/api/consumables", body, employee, 403);
        assertEquals(0, items.count());
    }
    @Test void ordinaryEmployeeCanReadCatalogWithoutAssetPermissionButCannotChangeStock() throws Exception {
        var account = employee("consumable-reader"); var item = create("鼠标");
        mvc.perform(get("/api/consumables").with(user(AccountPrincipal.from(account))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.canManage").value(false)).andExpect(jsonPath("$.items[0].quantity").value(0));
        mvc.perform(get("/api/consumables/{id}", item.get("id").asLong()).with(user(AccountPrincipal.from(account))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lowStock").value(true));
        postJson("/api/consumables/" + item.get("id").asLong() + "/movements", moveBody(item, "IN", 5, ""), account, 403);
        postJson("/api/consumables/" + item.get("id").asLong() + "/settings", settingsBody(item), account, 403);
    }
    @Test void freshPermissionRevocationAndDisabledAccountAreEnforced() throws Exception {
        var manager = employee("consumable-manager"); manager.setDataScope(DataScope.ALL);
        manager.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_EDIT, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN)); users.saveAndFlush(manager);
        var principal = AccountPrincipal.from(manager);
        mvc.perform(get("/api/consumables").with(user(principal))).andExpect(jsonPath("$.canManage").value(true));
        manager.setPermissions(Set.of(Permission.ASSET_VIEW)); users.saveAndFlush(manager);
        mvc.perform(post("/api/consumables").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createBody("禁止旧权限")))).andExpect(status().isForbidden());
        manager.setEnabled(false); users.saveAndFlush(manager);
        mvc.perform(get("/api/consumables").with(user(principal))).andExpect(status().isUnauthorized());
    }
    @Test void initialStockIsZeroAndCannotBeInjectedFromCreateBody() throws Exception {
        var body = createBody("网线"); body.put("quantity", 999);
        var item = postJson("/api/consumables", body, admin(), 201);
        assertEquals(0, item.get("quantity").asLong()); assertEquals(0, movements.count());
    }
    @Test void createIsIdempotentOnlyForTheSameActorAndParameters() throws Exception {
        var body = createBody("重复新增"); var original = postJson("/api/consumables", body, admin(), 201);
        var replay = postJson("/api/consumables", body, admin(), 201);
        assertEquals(original.get("id").asLong(), replay.get("id").asLong()); assertEquals(1, items.count());
        body.put("name", "不能换请求内容"); postJson("/api/consumables", body, admin(), 409);
        assertEquals(1, items.count());
    }
    @Test void stockInAndExplicitAccountCheckoutPreserveOldAssetsAndUsers() throws Exception {
        var employee = employee("consumable-checkout"); var sameName = employee("consumable-same-name");
        long assetCount = assets.count(); var userTime = employee.getUpdatedAt(); var item = create("电池");
        var stocked = move(item, "IN", 10, "").get("item");
        assertEquals(10, stocked.get("quantity").asLong());
        var result = move(stocked, "OUT", 3, employee.getUsername());
        assertEquals(7, result.get("item").get("quantity").asLong());
        assertEquals(employee.getId(), result.get("movement").get("recipientId").asLong());
        assertEquals(7, result.get("movement").get("balanceAfter").asLong());
        assertEquals(2, movements.count()); assertEquals(assetCount, assets.count());
        assertEquals(userTime, users.findById(employee.getId()).orElseThrow().getUpdatedAt());
        mvc.perform(get("/api/consumables/movements").with(user(AccountPrincipal.from(sameName))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void ordinaryLedgerIsAlwaysScopedBeforePaginationAndFiltering() throws Exception {
        var owner = employee("consumable-ledger-owner"); var other = employee("consumable-ledger-other");
        var item = move(create("员工流水"), "IN", 25, "").get("item");
        item = move(item, "OUT", 1, owner.getUsername()).get("item");
        for (int i = 0; i < 21; i++) item = move(item, "OUT", 1, other.getUsername()).get("item");
        mvc.perform(get("/api/consumables/movements").param("mine", "false").param("consumableId", item.get("id").asString())
                .with(user(AccountPrincipal.from(owner)))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1)).andExpect(jsonPath("$.items[0].recipientId").value(owner.getId()));
        mvc.perform(get("/api/consumables/movements").param("page", "1").with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/consumables/movements").with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(23)).andExpect(jsonPath("$.items.length()").value(20));
        mvc.perform(get("/api/consumables/movements").param("mine", "true").with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void checkoutRequiresEnabledUsernameNotDisplayNameAndNotes() throws Exception {
        var employee = employee("consumable-disabled"); employee.setEnabled(false); users.saveAndFlush(employee);
        var item = move(create("领用校验"), "IN", 5, "").get("item");
        String path = "/api/consumables/" + item.get("id").asLong() + "/movements";
        for (String invalid : List.of("", "同名员工", employee.getUsername())) postJson(path, moveBody(item, "OUT", 1, invalid), admin(), 400);
        var body = moveBody(item, "OUT", 1, admin().getUsername()); body.put("note", "  "); postJson(path, body, admin(), 400);
        postJson(path, moveBody(item, "IN", 1, admin().getUsername()), admin(), 400);
        assertEquals(5, items.findById(item.get("id").asLong()).orElseThrow().quantity); assertEquals(1, movements.count());
    }
    @Test void negativeZeroFractionalAndExcessiveQuantitiesAreRejected() throws Exception {
        var item = create("数量校验"); String path = "/api/consumables/" + item.get("id").asLong() + "/movements";
        for (Number invalid : List.of(-1, 0, 1_000_000_001L, 1.5)) {
            var body = moveBody(item, "IN", 1, ""); body.put("quantity", invalid); postJson(path, body, admin(), 400);
        }
        assertEquals(0, movements.count()); assertEquals(0, items.findById(item.get("id").asLong()).orElseThrow().quantity);
    }
    @Test void insufficientStockAndOverflowDoNotWriteLedger() throws Exception {
        var item = move(create("余额边界"), "IN", 1_000_000_000L, "").get("item");
        String path = "/api/consumables/" + item.get("id").asLong() + "/movements";
        postJson(path, moveBody(item, "IN", 1, ""), admin(), 409);
        assertEquals(1_000_000_000L, items.findById(item.get("id").asLong()).orElseThrow().quantity); assertEquals(1, movements.count());
        var empty = create("空库禁止领用");
        postJson("/api/consumables/" + empty.get("id").asLong() + "/movements", moveBody(empty, "OUT", 1, admin().getUsername()), admin(), 409);
        assertEquals(1, movements.count());
    }
    @Test void staleVersionDoesNotWriteStockOrLedger() throws Exception {
        var item = create("并发版本"); move(item, "IN", 5, "");
        postJson("/api/consumables/" + item.get("id").asLong() + "/movements", moveBody(item, "IN", 5, ""), admin(), 409);
        assertEquals(5, items.findById(item.get("id").asLong()).orElseThrow().quantity); assertEquals(1, movements.count());
    }
    @Test void duplicateMovementReturnsOriginalLedgerButDifferentPayloadConflicts() throws Exception {
        var item = create("流水幂等"); var body = moveBody(item, "IN", 10, "");
        String path = "/api/consumables/" + item.get("id").asLong() + "/movements";
        var original = postJson(path, body, admin(), 200); var replay = postJson(path, body, admin(), 200);
        assertEquals(original.get("movement").get("id").asLong(), replay.get("movement").get("id").asLong());
        assertEquals(10, replay.get("item").get("quantity").asLong()); assertEquals(1, movements.count());
        body.put("quantity", 9); postJson(path, body, admin(), 409); assertEquals(1, movements.count());
    }
    @Test void reusedMovementKeyCannotMutateAnotherItem() throws Exception {
        var first = create("原耗材"); var second = create("另一耗材"); var body = moveBody(first, "IN", 5, "");
        postJson("/api/consumables/" + first.get("id").asLong() + "/movements", body, admin(), 200);
        postJson("/api/consumables/" + second.get("id").asLong() + "/movements", body, admin(), 409);
        assertEquals(0, items.findById(second.get("id").asLong()).orElseThrow().quantity); assertEquals(1, movements.count());
    }
    @Test void settingsAreVersionedAndIdempotentAndCannotReinterpretNonzeroStockUnit() throws Exception {
        var item = create("历史名字"); var body = settingsBody(item); body.put("name", "新名称");
        String path = "/api/consumables/" + item.get("id").asLong() + "/settings";
        var updated = postJson(path, body, admin(), 200); postJson(path, body, admin(), 200);
        assertEquals(1, changes.count()); assertTrue(updated.get("version").asLong() > item.get("version").asLong());
        var stocked = move(updated, "IN", 5, "").get("item");
        var changeUnit = settingsBody(stocked); changeUnit.put("unit", "箱"); postJson(path, changeUnit, admin(), 409);
        assertEquals("个", items.findById(item.get("id").asLong()).orElseThrow().unit);
        var rename = settingsBody(stocked); rename.put("name", "现名称"); postJson(path, rename, admin(), 200);
        assertEquals("新名称", movements.findAll().getFirst().consumableName); assertEquals("个", movements.findAll().getFirst().unit);
    }
    @Test void searchEscapesWildcardsAndLowStockFilterUsesThreshold() throws Exception {
        create("100%电池"); var full = move(create("普通电池"), "IN", 8, "").get("item");
        mvc.perform(get("/api/consumables").param("query", "%").with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].name").value("100%电池"));
        mvc.perform(get("/api/consumables").param("lowStock", "true").with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        var threshold = move(full, "OUT", 6, admin().getUsername()).get("item"); assertTrue(threshold.get("lowStock").asBoolean());
    }
    @Test void malformedIdentifiersAndRequestKeysAreBadRequests() throws Exception {
        mvc.perform(get("/api/consumables/not-a-number").with(user(AccountPrincipal.from(admin())))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/consumables/movements").param("consumableId", "-1").with(user(AccountPrincipal.from(admin())))).andExpect(status().isBadRequest());
        var body = createBody("不完整UUID"); body.put("requestKey", "1-1-1-1-1"); postJson("/api/consumables", body, admin(), 400);
        assertEquals(0, items.count());
    }
    @Test void ledgerHasNoDeleteOrEditEndpoint() throws Exception {
        var item = move(create("不可删除流水"), "IN", 5, ""); long ledgerId = item.get("movement").get("id").asLong();
        mvc.perform(delete("/api/consumables/movements/{id}", ledgerId).with(user(AccountPrincipal.from(admin()))).with(csrf()))
                .andExpect(status().is4xxClientError());
        mvc.perform(put("/api/consumables/movements/{id}", ledgerId).with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":999}")).andExpect(status().is4xxClientError());
        assertEquals(1, movements.count());
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentManagersCannotBothSpendTheSameStock() throws Exception {
        var first = employee("consumable-race-a"); var second = employee("consumable-race-b");
        for (var account : List.of(first, second)) {
            account.setDataScope(DataScope.ALL); account.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_EDIT, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN));
            users.saveAndFlush(account);
        }
        Long itemId = null;
        try {
            var stocked = move(create("并发不超领"), "IN", 5, "").get("item"); itemId = stocked.get("id").asLong();
            Long id = itemId; long version = stocked.get("version").asLong(); var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var futures = new ArrayList<Future<Integer>>();
                for (var actor : List.of(first, second)) futures.add(pool.submit(() -> {
                    start.await(); var principal = AccountPrincipal.from(actor);
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "N/A", principal.getAuthorities()));
                    try {
                        service.move(id, new ConsumableRequest.Move(UUID.randomUUID().toString(), version, "OUT", new java.math.BigDecimal("4"), actor.getUsername(), "并发验收领用"));
                        return 200;
                    } catch (com.acme.assetmanagement.common.ApiException error) { return error.getStatus().value(); }
                    finally { SecurityContextHolder.clearContext(); }
                }));
                start.countDown(); var results = new ArrayList<Integer>();
                for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
                results.sort(Integer::compareTo); assertEquals(List.of(200, 409), results);
            }
            assertEquals(1, items.findById(itemId).orElseThrow().quantity);
            long outgoing = movements.findAll().stream().filter(m -> m.consumableId.equals(id) && m.kind.equals("OUT")).count();
            assertEquals(1, outgoing);
        } finally {
            // Only this test's committed in-memory rows are removed; no production datasource is used.
            if (itemId != null) {
                Long id = itemId; movements.deleteAll(movements.findAll().stream().filter(m -> m.consumableId.equals(id)).toList());
                items.deleteById(id);
            }
            users.deleteById(first.getId()); users.deleteById(second.getId());
        }
    }
}
