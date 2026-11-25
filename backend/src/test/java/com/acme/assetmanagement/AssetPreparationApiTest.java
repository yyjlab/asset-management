package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import jakarta.persistence.EntityManager;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:asset-preparation;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class AssetPreparationApiTest {
    @Autowired MockMvc mvc;
    @Autowired AssetRepository assets;
    @Autowired AssetPreparationRepository preparations;
    @Autowired LookupRepository lookups;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    private AccountPrincipal admin() { return AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow()); }
    private Asset device() {
        var seed = assets.findAll().getFirst(); var a = new Asset();
        a.setAssetTag(UUID.randomUUID().toString()); a.setName("整备测试电脑"); a.setCompany(seed.getCompany()); a.setModel(seed.getModel());
        a.setCategory(lookups.findByTypeAndNameIgnoreCase(LookupType.CATEGORY,"台式机").orElseThrow());
        a.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS,"可领用").orElseThrow()); a.setLocation(seed.getLocation());
        a.setDomainJoinStatus(DomainJoinStatus.JOINED); a.setCpu("原始 CPU"); a.setNotes("保留备注"); a.setPurchasePrice(new BigDecimal("3000.00"));
        return assets.saveAndFlush(a);
    }
    private JsonNode detail(Asset a) throws Exception {
        return json.readTree(mvc.perform(get("/api/asset-preparations/{id}",a.getId()).with(user(admin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private Map<String,Object> checks() {
        var checks = new LinkedHashMap<String,Object>(); for (var key:List.of("DATA","SYSTEM","DOMAIN","HARDWARE")) checks.put(key,Map.of("result","PASS","note","实物已核实")); return checks;
    }
    private Map<String,Object> payload(Asset a,String action) throws Exception {
        var d=detail(a).get("preparation"); var body=new LinkedHashMap<String,Object>(); body.put("version",d.get("version").asLong()); body.put("assetVersion",d.get("assetVersion").asLong());
        body.put("action",action); body.put("note",""); body.put("checks",checks()); return body;
    }
    private JsonNode act(Asset a,Map<String,Object> body,int expected) throws Exception {
        var response=mvc.perform(post("/api/asset-preparations/{id}",a.getId()).with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }
    @Test void readsDoNotBackfillOrChangeOldAssetsAndReadyRequiresExplicitChecks() throws Exception {
        var a=device(); var before=AssetResponse.from(a); long count=preparations.count();
        assertEquals("UNVERIFIED",detail(a).get("preparation").get("state").asText());
        mvc.perform(get("/api/asset-preparations").with(user(admin()))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        assertEquals(count,preparations.count()); assertEquals(before,AssetResponse.from(a));
        var ready=act(a,payload(a,"READY"),200); assertEquals("READY",ready.get("preparation").get("state").asText());
        assertEquals(before,AssetResponse.from(a)); assertEquals(1,ready.get("events").size()); assertEquals("",ready.get("note").asText());
        a.setNotes("仅编辑备注也需复核"); assets.saveAndFlush(a);
        assertEquals("UNVERIFIED",detail(a).get("preparation").get("state").asText());
    }
    @Test void invalidChecksCannotProduceReadyAndDoNotCreateRows() throws Exception {
        var a=device(); var body=payload(a,"READY"); var values=checks(); values.put("HARDWARE",Map.of("result","ISSUE","note","接口失效")); body.put("checks",values);
        act(a,body,409); assertFalse(preparations.findByAssetId(a.getId()).isPresent());
        values.put("HARDWARE",Map.of("result","NA","note","")); act(a,body,409);
        values.put("HARDWARE",Map.of("result","UNCHECKED","note","")); act(a,body,409);
        values.put("HARDWARE",Map.of("result","invented","note","")); act(a,body,400);
        assertFalse(preparations.findByAssetId(a.getId()).isPresent());
    }
    @Test void unjoinedDeviceAndActiveDefectsCannotPassReadiness() throws Exception {
        var a=device(); a.setDomainJoinStatus(DomainJoinStatus.NOT_JOINED); assets.saveAndFlush(a);
        act(a,payload(a,"READY"),409); assertFalse(preparations.findByAssetId(a.getId()).isPresent());
        a.setDomainJoinStatus(DomainJoinStatus.JOINED); a.setKnownDefects(List.of(new AssetKnownDefect(UUID.randomUUID().toString(),"屏幕损坏","测试",LocalDateTime.now(),null,null))); assets.saveAndFlush(a);
        act(a,payload(a,"READY"),409); assertEquals("BLOCKED",detail(a).get("preparation").get("state").asText()); assertEquals(1,a.getKnownDefects().size());
    }
    @Test void staleCustodyFieldsCannotBeMarkedReady() throws Exception {
        var a=device(); a.setAssignedTo("尚未核实的员工"); assets.saveAndFlush(a);
        act(a,payload(a,"READY"),409); assertEquals("BLOCKED",detail(a).get("preparation").get("state").asText());
        assertEquals("尚未核实的员工",a.getAssignedTo()); assertFalse(preparations.findByAssetId(a.getId()).isPresent());
    }

    @Test void nonDomainDevicesCanUseNotApplicableOnlyWithReason() throws Exception {
        var a=device(); a.setCategory(lookups.findByTypeAndNameIgnoreCase(LookupType.CATEGORY,"显示器").orElseThrow()); assets.saveAndFlush(a);
        var body=payload(a,"READY"); var values=checks(); values.put("DOMAIN",Map.of("result","NA","note","显示器不适用")); body.put("checks",values);
        assertEquals("READY",act(a,body,200).get("preparation").get("state").asText());
    }
    @Test void returnResetsChecksAndRetainsAllCyclesAndRejectsDuplicateReturn() throws Exception {
        var a=device(); act(a,payload(a,"READY"),200); a.setCheckedOut(true); a.setAssignedTo("原领用员工"); assets.saveAndFlush(a);
        assertEquals("CHECKED_OUT",detail(a).get("preparation").get("state").asText());
        act(a,payload(a,"START"),409);
        mvc.perform(post("/api/assets/{id}/return",a.getId()).with(user(admin())).with(csrf()).header("If-Match",a.getVersion())).andExpect(status().isOk());
        var returned=detail(a); assertEquals("PENDING",returned.get("preparation").get("state").asText()); assertEquals("原领用员工",returned.get("preparation").get("previousHolder").asText());
        assertEquals("UNCHECKED",returned.get("checks").get("HARDWARE").get("result").asText()); assertEquals(2,returned.get("events").size()); assertEquals(2,returned.get("events").get(1).get("cycle").asInt());
        mvc.perform(post("/api/assets/{id}/return",a.getId()).with(user(admin())).with(csrf()).header("If-Match",a.getVersion())).andExpect(status().isConflict());
        assertEquals(2,detail(a).get("events").size());
        mvc.perform(delete("/api/assets/{id}",a.getId()).with(user(admin())).with(csrf())).andExpect(status().isConflict()); assertTrue(assets.existsById(a.getId()));
    }
    @Test void optimisticVersionsRejectStaleUpdatesAndReturnFailureCreatesNoChecklist() throws Exception {
        var a=device(); var old=payload(a,"START"); act(a,old,200); act(a,old,409);
        var stale=payload(a,"READY"); a.setNotes("并发修改"); assets.saveAndFlush(a); act(a,stale,409);
        var b=device(); b.setCheckedOut(true); b.setAssignedTo("员工"); assets.saveAndFlush(b);
        mvc.perform(post("/api/assets/{id}/return",b.getId()).with(user(admin())).with(csrf()).header("If-Match",b.getVersion()+1)).andExpect(status().isConflict());
        assertFalse(preparations.findByAssetId(b.getId()).isPresent()); assertTrue(b.isCheckedOut());
    }
    @Test void irreparableEquipmentRequiresReasonAndSeparateScrapConfirmationWithoutDataLoss() throws Exception {
        var a=device(); var body=payload(a,"SCRAP"); body.put("note","无法修复"); act(a,body,409);
        body=payload(a,"REQUEST_SCRAP"); act(a,body,409); body.put("note","主板损坏，不值得维修"); act(a,body,200);
        assertEquals("RETIREMENT_PENDING",detail(a).get("preparation").get("state").asText()); assertEquals("维修中",a.getStatus().getName());
        body=payload(a,"SCRAP"); body.put("note","运维确认报废"); act(a,body,200);
        assertEquals("已报废",a.getStatus().getName()); assertEquals("SCRAPPED",detail(a).get("preparation").get("state").asText());
        assertEquals("原始 CPU",a.getCpu()); assertEquals("保留备注",a.getNotes()); assertEquals(0,new BigDecimal("3000").compareTo(a.getPurchasePrice())); assertTrue(assets.existsById(a.getId()));
    }
    @Test void permissionsScopesCsrfAndPublicQrProtectInternalRecords() throws Exception {
        var a=device(); var principal=admin();
        mvc.perform(get("/api/asset-preparations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/asset-preparations/{id}",a.getId()).with(user(principal)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload(a,"START")))).andExpect(status().isForbidden());
        var reader=new UserAccount(); reader.setUsername("prep-reader"); reader.setDisplayName("整备只读员工"); reader.setPasswordHash("unused"); reader.setAccessConfigured(true); reader.setPermissions(Set.of(Permission.ASSET_VIEW)); reader.setDataScope(DataScope.DEPARTMENT); reader.setDepartments(Set.of("IT")); users.saveAndFlush(reader);
        a.setOwnershipDepartment("it"); assets.saveAndFlush(a);
        mvc.perform(get("/api/asset-preparations/{id}",a.getId()).with(user(AccountPrincipal.from(reader)))).andExpect(status().isNotFound());
        a.setOwnershipDepartment("IT"); assets.saveAndFlush(a);
        mvc.perform(get("/api/asset-preparations/{id}",a.getId()).with(user(AccountPrincipal.from(reader)))).andExpect(status().isOk());
        mvc.perform(post("/api/asset-preparations/{id}",a.getId()).with(user(AccountPrincipal.from(reader))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload(a,"START")))).andExpect(status().isForbidden());
        act(a,payload(a,"START"),200);
        mvc.perform(get("/api/public/assets/{token}",a.getQrToken())).andExpect(status().isOk()).andExpect(jsonPath("$.preparation").doesNotExist()).andExpect(jsonPath("$.events").doesNotExist());
    }
}
