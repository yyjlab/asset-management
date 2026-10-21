package com.acme.assetmanagement.operations;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:oadb;DB_CLOSE_DELAY=-1",
        "app.dingtalk-login.enabled=true","app.dingtalk-login.client-id=test-client","app.dingtalk-login.client-secret=test-secret",
        "app.dingtalk-login.redirect-uri=http://localhost/api/v1/auth/dingtalk/callback",
        "app.dingtalk-oa.enabled=true","app.dingtalk-oa.stream-enabled=false","app.dingtalk-oa.corp-id=test-corp",
        "app.dingtalk-oa.issue-process-code=issue-code","app.dingtalk-oa.return-process-code=return-code",
        "app.dingtalk-oa.it-approver-user-ids=it-user"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DingTalkOaTest {
    @Autowired DingTalkOaService service;
    @Autowired OperationRepository tasks;
    @Autowired AssetRepository assets;
    @Autowired UserRepository users;
    @Autowired LookupRepository lookups;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @MockitoBean DingTalkOaClient client;
    @MockitoBean DingTalkOaStream stream;
    TransactionTemplate tx;
    UserAccount owner;
    @BeforeEach void init() {
        tx=new TransactionTemplate(transactions);
        owner=employee(); auth(owner);
        when(client.employee(anyString())).thenReturn(new DingTalkOaClient.Employee("employee-user",1));
        when(client.start(anyString(),any(),anyString(),anyString())).thenAnswer(inv -> new DingTalkOaClient.Start("instance-"+UUID.randomUUID()));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    UserAccount employee() {
        var u=new UserAccount(); u.setUsername("oa-"+UUID.randomUUID()); u.setDisplayName("同名员工");
        u.setPasswordHash("not-used"); u.setAccessConfigured(true); u.setPermissions(Set.of(Permission.ASSET_VIEW)); u.setDataScope(DataScope.SELF);
        u.setDingtalkIdentity(UUID.randomUUID().toString()); u.setDingtalkUnionId("union-"+UUID.randomUUID()); return users.saveAndFlush(u);
    }
    void auth(UserAccount user) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(AccountPrincipal.from(user),null,AccountPrincipal.from(user).getAuthorities())); }
    Asset asset(boolean owned) {
        return tx.execute(s -> {
            var seed=assets.findAll().getFirst(); var a=new Asset(); a.setAssetTag("OA-"+UUID.randomUUID()); a.setName("实物交接测试");
            a.setCompany(seed.getCompany()); a.setModel(seed.getModel()); a.setCategory(seed.getCategory()); a.setLocation(seed.getLocation());
            a.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS,"当前可用").orElseGet(() -> lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS,"可领用").orElseThrow()));
            a.setPurchasePrice(new java.math.BigDecimal("1234.56")); a.setImageUrl("/history.png"); a.setCpu("原始CPU");
            if(owned) { a.setAssignedUserId(owner.getId()); a.setAssignedTo(owner.getDisplayName()); a.setCheckedOut(true); }
            return assets.saveAndFlush(a);
        });
    }
    DingTalkOaService.Create body(Asset a,String kind) { return new DingTalkOaService.Create(UUID.randomUUID().toString(),kind,a.getId(),a.getVersion(),"现场核对电脑和电源",true); }
    JsonNode remote(Long id,String state,String result,String approver) {
        return tx.execute(s -> {
            var t=tasks.findById(id).orElseThrow();
            return json.valueToTree(Map.of("originatorUserId",t.oaUserId,"status",state,"result",result,
                    "formComponentValues",List.of(Map.of("name",DingTalkOaClient.KEY_FIELD,"value",t.requestKey),
                            Map.of("name",DingTalkOaClient.DETAIL_FIELD,"value",t.oaFormValue),
                            Map.of("name",DingTalkOaClient.CONFIRM_FIELD,"value",DingTalkOaClient.ATTESTATION)),
                    "tasks",List.of(Map.of("userId",approver,"status","COMPLETED","result","agree"))));
        });
    }
    void response(DingTalkOaService.Item item,String state,String result,String approver) { doReturn(remote(item.id(),state,result,approver)).when(client).detail(item.instanceId()); }

    @Test void requiresAuthenticationAndCsrf() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/dingtalk-oa/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/dingtalk-oa").with(user(AccountPrincipal.from(owner))).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void submissionReservesButNeverChangesLedgerAndReplayDoesNotCreateAgain() {
        var a=asset(false); var request=body(a,"ISSUE"); var item=service.create(request);
        assertEquals("WAITING",item.approvalState()); assertFalse(assets.findById(a.getId()).orElseThrow().isCheckedOut());
        assertEquals(item.id(),service.create(request).id()); verify(client,times(1)).start(anyString(),any(),anyString(),anyString());
        assertThrows(RuntimeException.class,() -> service.create(body(a,"ISSUE")));
    }
    @Test void officialApprovalAppliesOnceAndPreservesHistoricalFields() {
        var a=asset(false); var item=service.create(body(a,"ISSUE")); response(item,"COMPLETED","agree","it-user");
        assertEquals("APPLIED",service.sync(item.id()).approvalState()); var after=assets.findById(a.getId()).orElseThrow();
        assertEquals(owner.getId(),after.getAssignedUserId()); assertTrue(after.isCheckedOut());
        assertEquals("原始CPU",after.getCpu()); assertEquals("/history.png",after.getImageUrl()); assertEquals(new java.math.BigDecimal("1234.56"),after.getPurchasePrice());
        long version=after.getVersion(); service.sync(item.id()); service.signal("test-corp","issue-code",item.instanceId()); service.sync(item.id());
        assertEquals(version,assets.findById(a.getId()).orElseThrow().getVersion());
        assertFalse(tasks.existsByReservedAssetId(a.getId()));
    }
    @Test void pendingRefusedAndTerminatedNeverChangeLedger() {
        for (String end:List.of("refuse","terminate")) {
            var a=asset(false); var item=service.create(body(a,"ISSUE")); response(item,"RUNNING","","it-user");
            assertEquals("WAITING",service.sync(item.id()).approvalState());
            response(item,"terminate".equals(end)?"TERMINATED":"COMPLETED",end,"it-user");
            assertEquals("terminate".equals(end)?"TERMINATED":"REFUSED",service.sync(item.id()).approvalState());
            assertFalse(assets.findById(a.getId()).orElseThrow().isCheckedOut()); assertFalse(tasks.existsByReservedAssetId(a.getId()));
        }
    }
    @Test void approvalWithoutIndependentItPhysicalCheckCannotChangeLedger() {
        var a=asset(false); var item=service.create(body(a,"ISSUE")); response(item,"COMPLETED","agree","employee-user");
        assertEquals("LEDGER_FAILED",service.sync(item.id()).approvalState());
        assertFalse(assets.findById(a.getId()).orElseThrow().isCheckedOut()); assertTrue(tasks.existsByReservedAssetId(a.getId()));
    }
    @Test void changedAssetIsNotOverwrittenEvenWhenRetried() {
        var a=asset(false); var item=service.create(body(a,"ISSUE"));
        tx.executeWithoutResult(s -> { var changed=assets.findById(a.getId()).orElseThrow(); changed.setCpu("他人修改"); assets.saveAndFlush(changed); });
        response(item,"COMPLETED","agree","it-user");
        assertEquals("LEDGER_FAILED",service.sync(item.id()).approvalState()); assertEquals("LEDGER_FAILED",service.sync(item.id()).approvalState());
        var after=assets.findById(a.getId()).orElseThrow(); assertFalse(after.isCheckedOut()); assertEquals("他人修改",after.getCpu());
    }
    @Test void inactiveOwnerCannotReceiveAssetsAfterApproval() {
        var a=asset(false); var item=service.create(body(a,"ISSUE")); response(item,"COMPLETED","agree","it-user");
        owner.setEnabled(false); users.saveAndFlush(owner); service.refresh(item.id());
        assertEquals("LEDGER_FAILED",tasks.findById(item.id()).orElseThrow().oaState); assertFalse(assets.findById(a.getId()).orElseThrow().isCheckedOut());
    }
    @Test void returnUsesStableUserIdAndPreservesMaintenanceState() {
        var a=asset(true);
        var changed=tx.execute(s -> { var existing=assets.findById(a.getId()).orElseThrow();
            existing.setStatus(lookups.findByTypeAndNameIgnoreCase(LookupType.STATUS,"维护中")
                    .orElseGet(() -> lookups.saveAndFlush(new LookupValue(LookupType.STATUS,"维护中"))));
            return assets.saveAndFlush(existing); });
        var item=service.create(body(changed,"RETURN")); response(item,"COMPLETED","agree","it-user");
        assertEquals("APPLIED",service.sync(item.id()).approvalState());
        tx.executeWithoutResult(s -> { var after=assets.findById(a.getId()).orElseThrow();
            assertFalse(after.isCheckedOut()); assertNull(after.getAssignedUserId()); assertNull(after.getAssignedTo());
            assertEquals("维护中",after.getStatus().getName()); assertEquals("原始CPU",after.getCpu()); });
    }
    @Test void sameNameCannotReturnOrReadAnotherEmployeesTask() throws Exception {
        var a=asset(true); var request=body(a,"RETURN"); var item=service.create(request); var other=employee(); auth(other);
        assertThrows(RuntimeException.class,() -> service.create(body(a,"RETURN")));
        assertTrue(service.list(0,false).items().stream().noneMatch(t -> t.id().equals(item.id())));
        mvc.perform(post("/api/dingtalk-oa/"+item.id()+"/sync").with(user(AccountPrincipal.from(other))).with(csrf()))
                .andExpect(status().isNotFound());
    }
    @Test void timeoutNeverBlindlyRetriesCreationAndAdministratorCanRecoverExactInstance() {
        var a=asset(false); var body=body(a,"ISSUE");
        when(client.start(anyString(),any(),anyString(),anyString())).thenThrow(new RuntimeException("sensitive-token"));
        var item=service.create(body); assertEquals("START_UNCERTAIN",item.approvalState()); assertFalse(item.error().contains("sensitive-token"));
        service.create(body); verify(client,times(1)).start(anyString(),any(),anyString(),anyString());
        when(client.detail("recovered-instance")).thenReturn(remote(item.id(),"RUNNING","","it-user"));
        assertThrows(RuntimeException.class,() -> service.attach(item.id(),"recovered-instance"));
        auth(users.findByUsernameIgnoreCase("admin").orElseThrow());
        assertEquals("WAITING",service.attach(item.id(),"recovered-instance").approvalState());
    }
    @Test void mutatedOaFormOrApplicantCannotAuthorizeAssetMutation() {
        var a=asset(false); var item=service.create(body(a,"ISSUE"));
        var remote=remote(item.id(),"COMPLETED","agree","it-user").deepCopy();
        ((tools.jackson.databind.node.ObjectNode)remote).put("originatorUserId","another-employee");
        when(client.detail(item.instanceId())).thenReturn(remote);
        assertEquals("LEDGER_FAILED",service.sync(item.id()).approvalState()); assertFalse(assets.findById(a.getId()).orElseThrow().isCheckedOut());
        remote=remote(item.id(),"COMPLETED","agree","it-user").deepCopy();
        ((tools.jackson.databind.node.ObjectNode)remote.path("formComponentValues").get(1)).put("value","changed asset list");
        when(client.detail(item.instanceId())).thenReturn(remote);
        assertEquals("LEDGER_FAILED",service.sync(item.id()).approvalState()); assertFalse(assets.findById(a.getId()).orElseThrow().isCheckedOut());
    }
    @Test void providerFailureIsSafeAndRetriedWithoutChangingAsset() {
        var a=asset(false); var item=service.create(body(a,"ISSUE")); when(client.detail(item.instanceId())).thenThrow(new RuntimeException("sensitive-token"));
        var failed=service.sync(item.id()); assertEquals("CHECK_FAILED",failed.approvalState()); assertFalse(failed.error().contains("sensitive-token"));
        response(item,"COMPLETED","agree","it-user"); assertEquals("APPLIED",service.sync(item.id()).approvalState());
    }
    @Test void localApprovalEndpointCannotBypassOa() throws Exception {
        var item=service.create(body(asset(false),"ISSUE")); var admin=users.findByUsernameIgnoreCase("admin").orElseThrow();
        mvc.perform(post("/api/operations/"+item.id()+"/actions").with(user(AccountPrincipal.from(admin))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"action\":\"APPROVE\",\"note\":\"bypass\"}"))
                .andExpect(status().isConflict());
    }
    @Test void unrelatedHrAndOtherEnterpriseEventsAreIgnored() {
        var item=service.create(body(asset(false),"ISSUE"));
        tx.executeWithoutResult(s -> { var t=tasks.findById(item.id()).orElseThrow(); t.oaDirty=false; tasks.saveAndFlush(t); });
        service.signal("other-corp","issue-code",item.instanceId()); service.signal("test-corp","intelligent-hr-code",item.instanceId());
        service.signal("test-corp","issue-code","unknown-hr-instance");
        assertEquals(Boolean.FALSE,tasks.findById(item.id()).orElseThrow().oaDirty);
    }
    @Test void administratorCanCloseExceptionWithoutChangingLedgerAndLaterCallbackCannotReopenIt() {
        var a=asset(false); var item=service.create(body(a,"ISSUE")); response(item,"COMPLETED","agree","unexpected-approver");
        var failed=service.sync(item.id());
        assertThrows(RuntimeException.class,() -> service.closeException(failed.id(),failed.version(),"核实后重建申请"));
        auth(users.findByUsernameIgnoreCase("admin").orElseThrow());
        assertThrows(RuntimeException.class,() -> service.closeException(failed.id(),failed.version()-1,"旧版本"));
        assertEquals("SKIPPED",service.closeException(failed.id(),failed.version(),"核对后重新发起，保留审批历史").approvalState());
        response(item,"COMPLETED","agree","it-user"); service.signal("test-corp","issue-code",item.instanceId()); service.sync(item.id());
        assertEquals("SKIPPED",tasks.findById(item.id()).orElseThrow().oaState);
        assertFalse(tasks.existsByReservedAssetId(a.getId())); assertFalse(assets.findById(a.getId()).orElseThrow().isCheckedOut());
    }
    @Test void physicalConfirmationIsMandatoryAndRebindingBlocksExecution() throws Exception {
        var a=asset(false); var body=body(a,"ISSUE");
        mvc.perform(post("/api/dingtalk-oa").with(user(AccountPrincipal.from(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new DingTalkOaService.Create(body.requestKey(),body.kind(),body.assetId(),body.assetVersion(),body.description(),false))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
        auth(owner); // MockMvc clears its request SecurityContext after the rejected POST.
        var item=service.create(body); response(item,"COMPLETED","agree","it-user");
        owner.setDingtalkIdentity(null); users.saveAndFlush(owner);
        assertNull(users.findById(owner.getId()).orElseThrow().getDingtalkUnionId());
        assertEquals("LEDGER_FAILED",service.sync(item.id()).approvalState());
        assertFalse(assets.findById(a.getId()).orElseThrow().isCheckedOut());
    }
    @Test void catalogueIsMinimalAndReturnOptionsNeverMatchByName() throws Exception {
        var own=asset(true); var other=employee(); var someoneElse=asset(false);
        someoneElse.setAssignedUserId(other.getId()); someoneElse.setAssignedTo(owner.getDisplayName()); someoneElse.setCheckedOut(true); assets.saveAndFlush(someoneElse);
        var candidates=service.candidates("RETURN");
        assertTrue(candidates.stream().anyMatch(a -> a.id().equals(own.getId())));
        assertFalse(candidates.stream().anyMatch(a -> a.id().equals(someoneElse.getId())));
        mvc.perform(get("/api/dingtalk-oa/candidates?kind=RETURN").with(user(AccountPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].purchasePrice").doesNotExist())
                .andExpect(jsonPath("$[0].assignedTo").doesNotExist()).andExpect(jsonPath("$[0].manufacturerSerialNumber").doesNotExist());
    }
}
