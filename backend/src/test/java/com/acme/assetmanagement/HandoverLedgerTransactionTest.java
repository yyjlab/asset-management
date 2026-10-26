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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No wrapping test transaction: every HTTP mutation must commit or roll back on its own. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:handover-ledger-transactions;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HandoverLedgerTransactionTest {
    @Autowired MockMvc mvc;
    @Autowired AssetRepository assets;
    @Autowired UserRepository users;
    @Autowired LookupRepository lookups;
    @Autowired HandoverRepository handovers;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    record Fixture(UserAccount employee, List<Long> ids, Map<Long, Long> versions) {}
    private UserAccount admin() { return users.findByUsernameIgnoreCase("admin").orElseThrow(); }
    private Fixture fixture() {
        return new TransactionTemplate(transactions).execute(tx -> {
            String key = UUID.randomUUID().toString();
            var employee = new UserAccount(); employee.setUsername("h-" + key); employee.setDisplayName("事务交接员工");
            employee.setPasswordHash("not-a-login-password"); employee.setAccessConfigured(true);
            employee.setDataScope(DataScope.SELF); employee.setPermissions(Set.of(Permission.ASSET_VIEW)); users.saveAndFlush(employee);
            var seed = assets.findAll().getFirst(); var ids = new ArrayList<Long>(); var versions = new LinkedHashMap<Long, Long>();
            for (int index = 0; index < 2; index++) {
                var device = new Asset(); device.setAssetTag(key + "-" + index); device.setName("事务测试电脑");
                device.setCompany(seed.getCompany()); device.setCategory(seed.getCategory()); device.setModel(seed.getModel());
                device.setLocation(seed.getLocation()); device.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "可领用")
                        .or(() -> lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS, "当前可用")).orElseThrow());
                device.setCpu("原CPU"); assets.saveAndFlush(device); ids.add(device.getId()); versions.put(device.getId(), device.getVersion());
            }
            return new Fixture(employee, ids, versions);
        });
    }
    private Map<String, Object> request(Fixture f) {
        return Map.of("requestKey", UUID.randomUUID().toString(), "kind", "ONBOARDING", "employeeUserId", f.employee().getId(),
                "employeeName", f.employee().getDisplayName(), "ledgerEnabled", true, "assetIds", f.ids(),
                "assetVersions", f.versions(), "manualItems", List.of(), "includeAssignedAssets", false, "allowLegacyAssets", false);
    }
    private JsonNode create(Map<String, Object> body, int expected) throws Exception {
        var result = mvc.perform(post("/api/handovers").with(user(AccountPrincipal.from(admin()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(result.getContentAsString());
    }
    private JsonNode action(JsonNode task, String path, UserAccount actor, Map<String, Object> extra, int expected) throws Exception {
        var body = new LinkedHashMap<>(extra); body.put("version", task.get("version").asLong());
        return json.readTree(mvc.perform(post("/api/handovers/{id}" + path, task.get("id").asLong())
                .with(user(AccountPrincipal.from(actor))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private void changeSecond(Fixture f) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var device = assets.findById(f.ids().get(1)).orElseThrow(); device.setCpu("其他人已修改CPU"); assets.saveAndFlush(device);
        });
    }

    @Test void conflictOnSecondDeviceRollsBackFirstDeviceOperationAuditAndParentCompletion() throws Exception {
        var f = fixture(); var task = create(request(f), 201);
        for (int index = 0; index < 2; index++) {
            task = action(task, "/items/" + index + "/check", admin(), Map.of("checked", true), 200);
            task = action(task, "/items/" + index + "/confirm", f.employee(), Map.of("offline", false), 200);
        }
        changeSecond(f);
        action(task, "/complete", admin(), Map.of("confirmed", true), 409);
        for (Long id : f.ids()) {
            mvc.perform(get("/api/assets/{id}", id).with(user(AccountPrincipal.from(admin()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.checkedOut").value(false))
                    .andExpect(jsonPath("$.assignedUserId").isEmpty());
        }
        assertEquals(f.versions().get(f.ids().getFirst()), assets.findById(f.ids().getFirst()).orElseThrow().getVersion());
        assertEquals("其他人已修改CPU", assets.findById(f.ids().get(1)).orElseThrow().getCpu());
        mvc.perform(get("/api/handovers/{id}", task.get("id").asLong()).with(user(AccountPrincipal.from(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.ledgerAppliedAt").isEmpty()).andExpect(jsonPath("$.version").value(task.get("version").asLong()));
        for (var item : task.get("items")) {
            mvc.perform(get("/api/operations/{id}", item.get("operationId").asLong()).with(user(AccountPrincipal.from(admin()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.task.state").value("HANDOVER_PENDING"));
        }
        action(task, "/cancel", admin(), Map.of("note", "重新核对变更后的设备"), 200);
    }

    @Test void failedCreationDoesNotLeaveParentOrReservationAndRetryKeyDoesNotDuplicateChildren() throws Exception {
        var f = fixture(); changeSecond(f); long before = handovers.count();
        create(request(f), 409); assertEquals(before, handovers.count());
        var body = new LinkedHashMap<>(request(f)); body.put("assetIds", List.of(f.ids().getFirst()));
        var first = create(body, 201); var retry = create(body, 201);
        assertEquals(first.get("id").asLong(), retry.get("id").asLong());
        assertEquals(first.get("items").get(0).get("operationId").asLong(), retry.get("items").get(0).get("operationId").asLong());
        assertEquals(before + 1, handovers.count());
        action(first, "/cancel", admin(), Map.of("note", "事务验证完成"), 200);
    }
}
