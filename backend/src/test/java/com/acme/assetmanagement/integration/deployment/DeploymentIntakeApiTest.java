package com.acme.assetmanagement.integration.deployment;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.lookup.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ad-intake-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "app.ad-deployment.enabled=true", "app.asset-images.directory=${java.io.tmpdir}/asset-intake-test-images"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeploymentIntakeApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AssetRepository assets;
    @Autowired LookupRepository lookups;
    @Autowired DeploymentIntakeRepository intakes;
    @Autowired DeploymentCredentialService credentials;
    @Autowired DeploymentProperties properties;
    @Autowired com.acme.assetmanagement.setting.SystemSettingRepository settings;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.acme.assetmanagement.audit.AuditLogRepository auditLogs;
    @Autowired WindowsCollectionPackageRepository windowsPackages;
    @Autowired DeploymentCredentialRepository credentialRows;
    String token, externalId;
    Long categoryId;
    AccountPrincipal admin;
    @BeforeEach void prepare() {
        settings.deleteById(DeploymentAccessService.KEY); properties.setEnabled(true);
        for (var item : Map.of(LookupType.COMPANY, "微丰启", LookupType.LOCATION, "公司", LookupType.STATUS, "当前可用").entrySet())
            option(item.getKey(), item.getValue());
        categoryId = option(LookupType.CATEGORY, "笔记本电脑").getId();
        token = credentials.issue("intake-service", Instant.now().plusSeconds(3600)).token();
        admin = AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
        externalId = "ad:test:" + UUID.randomUUID() + ":1";
    }
    LookupValue option(LookupType type, String name) {
        return lookups.findByTypeAndNameIgnoreCase(type, name).orElseGet(() -> lookups.saveAndFlush(new LookupValue(type, name)));
    }
    Map<String, Object> request() {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("model", null); snapshot.put("cpu", "Intel Core i5"); snapshot.put("memory", "16 GiB");
        snapshot.put("manufacturerSerialNumber", "DESCRIPTIVE-SN");
        snapshot.put("internalDisks", List.of(Map.of("model", "NVMe SSD", "capacityBytes", 512L * 1073741824, "busType", "NVMe")));
        snapshot.put("graphicsCards", List.of("Microsoft Basic Display Adapter"));
        var value = new LinkedHashMap<String, Object>(); value.put("externalRegistrationId", externalId);
        value.put("capturedAt", "2026-01-01T00:00:00Z"); value.put("operator", Map.of("id", "pe-auto", "displayName", "PE 自动采集"));
        value.put("snapshot", snapshot); return value;
    }
    Map<String, Object> review() {
        return new LinkedHashMap<>(Map.of("revision", 0, "categoryId", categoryId, "name", "笔记本电脑", "model", "ThinkPad T14",
                "cpu", "Intel Core i5", "memory", "16 GiB", "storage", "NVMe 512 GiB", "manufacturerSerialNumber", "DESCRIPTIVE-SN", "note", "核对完成"));
    }
    ResultActions receive(Map<String, Object> body) throws Exception {
        return mvc.perform(post(DeploymentSecurityConfig.INTAKES).secure(true).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }
    ResultActions decide(Map<String, Object> body, String action) throws Exception {
        return mvc.perform(post("/api/settings/ad-deployment/intakes/" + externalId + "/" + action).with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }
    @Test void receivesIncompleteConfigurationWithoutNumberOrAssetAndExposesPendingReview() throws Exception {
        long count = assets.count();
        receive(request()).andExpect(status().isAccepted()).andExpect(jsonPath("$.result").value("pending_review"))
                .andExpect(jsonPath("$.asset").isEmpty());
        assertEquals(count, assets.count()); assertNull(intakes.findById(externalId).orElseThrow().assetTag);
        mvc.perform(get("/api/settings/ad-deployment/intakes").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].model").isEmpty())
                .andExpect(jsonPath("$.items[0].graphicsCard").isEmpty())
                .andExpect(jsonPath("$.items[0].declaredOperator.id").value("pe-auto"));
        mvc.perform(get("/api/settings/ad-deployment/status").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endpoint").value(DeploymentSecurityConfig.INTAKES))
                .andExpect(jsonPath("$.lastReceivedAt").isNotEmpty());
    }
    @Test void readableAssetNameIsIndependentOfModelAndUsedAfterReview() throws Exception {
        var body = request(); body.put("name", "LENOVO ThinkPad L13");
        @SuppressWarnings("unchecked") var snapshot = (Map<String, Object>)body.get("snapshot");
        snapshot.put("model", "20R4S4E200");
        receive(body).andExpect(status().isAccepted());
        mvc.perform(get("/api/settings/ad-deployment/intakes").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("LENOVO ThinkPad L13"))
                .andExpect(jsonPath("$.items[0].model").value("20R4S4E200"));
        var approval = review(); approval.put("name", "LENOVO ThinkPad L13"); approval.put("model", "20R4S4E200");
        decide(approval, "approve").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("LENOVO ThinkPad L13"));
        var asset = assets.findById(intakes.findById(externalId).orElseThrow().assetId).orElseThrow();
        assertEquals("LENOVO ThinkPad L13", asset.getName());
        assertEquals("20R4S4E200", asset.getModel().getName());
    }

    @Test void repeatIntakeDoesNotDuplicateAndChangedContentIsRejected() throws Exception {
        var body = request(); receive(body).andExpect(status().isAccepted());
        long count = intakes.count(); receive(body).andExpect(status().isAccepted()); assertEquals(count, intakes.count());
        body.put("operator", Map.of("id", "different", "displayName", "different"));
        receive(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
    @Test void approvalUsesCorrectedFieldsAndAllocatesExactlyOneAssetWithSafeDefaults() throws Exception {
        var body = request(); receive(body).andExpect(status().isAccepted());
        long before = assets.count(); var approval = review();
        decide(approval, "approve").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("approved"))
                .andExpect(jsonPath("$.asset.assetTag").value(org.hamcrest.Matchers.matchesPattern("[0-9]{12}")));
        var row = intakes.findById(externalId).orElseThrow(); var asset = assets.findById(row.assetId).orElseThrow();
        assertEquals(before + 1, assets.count()); assertEquals("ThinkPad T14", asset.getModel().getName());
        assertEquals("微丰启", asset.getCompany().getName()); assertEquals("公司", asset.getLocation().getName());
        assertEquals("当前可用", asset.getStatus().getName()); assertFalse(asset.isCheckedOut());
        assertNull(asset.getAssignedTo()); assertNull(asset.getAssignedUserId()); assertNull(asset.getDomainJoinStatus());
        assertNull(asset.getPurchasePrice()); assertNull(asset.getOrderNumber());
        decide(approval, "approve").andExpect(status().isOk()).andExpect(jsonPath("$.asset.id").value(row.assetId));
        assertEquals(before + 1, assets.count());
        receive(body).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("approved"));
        mvc.perform(get(DeploymentSecurityConfig.INTAKES + "/" + externalId).secure(true).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.asset.assetTag").value(row.assetTag));
        approval.put("model", "Changed after approval");
        decide(approval, "approve").andExpect(status().isConflict());
    }
    @Test void rejectionIsStickyAndNeverCreatesAsset() throws Exception {
        var body = request(); receive(body).andExpect(status().isAccepted()); long before = assets.count();
        var rejection = Map.<String, Object>of("revision", 0, "note", "不是新增设备");
        decide(rejection, "reject").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("rejected"));
        decide(rejection, "reject").andExpect(status().isOk());
        receive(body).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("rejected"));
        decide(review(), "approve").andExpect(status().isConflict()); assertEquals(before, assets.count());
    }
    @Test void rejectedIntakeCanBeCorrectedAndResubmittedWithoutCreatingOrDuplicatingAssets() throws Exception {
        var body = request(); receive(body).andExpect(status().isAccepted());
        long before = assets.count(); String original = intakes.findById(externalId).orElseThrow().requestJson;
        decide(Map.of("revision", 0, "note", "主机型号不对"), "reject").andExpect(status().isOk());
        var correction = review(); correction.put("revision", 1); correction.put("name", "LENOVO ThinkPad L13");
        correction.put("model", "ThinkPad L13"); correction.put("graphicsCard", "集成显卡");
        decide(correction, "resubmit").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("pending_review"))
                .andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.name").value("LENOVO ThinkPad L13"))
                .andExpect(jsonPath("$.reviewNote").value("主机型号不对")).andExpect(jsonPath("$.asset").isEmpty());
        decide(correction, "resubmit").andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        assertEquals(before, assets.count());
        assertEquals(original, intakes.findById(externalId).orElseThrow().requestJson);
        receive(body).andExpect(status().isAccepted()).andExpect(jsonPath("$.result").value("pending_review"));
        var wrong = new LinkedHashMap<>(correction); wrong.put("model", "Different correction");
        decide(wrong, "resubmit").andExpect(status().isConflict());
        decide(review(), "approve").andExpect(status().isConflict());
        var approval = new LinkedHashMap<>(correction); approval.put("revision", 2);
        decide(approval, "approve").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("approved"));
        decide(approval, "approve").andExpect(status().isOk());
        decide(correction, "resubmit").andExpect(status().isConflict());
        assertEquals(before + 1, assets.count());
        var asset = assets.findById(intakes.findById(externalId).orElseThrow().assetId).orElseThrow();
        assertEquals("LENOVO ThinkPad L13", asset.getName()); assertEquals("集成显卡", asset.getGraphicsCard());
        var audits = auditLogs.findAll().stream().filter(v -> externalId.equals(v.getTargetLabel())).toList();
        assertTrue(audits.stream().anyMatch(v -> v.getChangesJson().contains("主机型号不对")));
        assertEquals(1, audits.stream().filter(v -> v.getChangesJson().contains("\"result\":\"resubmitted\"")).count());
    }
    @Test void resubmissionChecksStateRevisionFieldsRoleAndCsrf() throws Exception {
        receive(request()).andExpect(status().isAccepted());
        decide(review(), "resubmit").andExpect(status().isConflict());
        decide(Map.of("revision", 0, "note", "型号待修正"), "reject").andExpect(status().isOk());
        decide(review(), "resubmit").andExpect(status().isConflict());
        var correction = review(); correction.put("revision", 1);
        var invalid = new LinkedHashMap<>(correction); invalid.put("model", "unknown");
        decide(invalid, "resubmit").andExpect(status().isUnprocessableEntity());
        invalid.put("model", "ThinkPad L13"); invalid.put("graphicsCard", "Microsoft Basic Display Adapter");
        decide(invalid, "resubmit").andExpect(status().isUnprocessableEntity());
        invalid = new LinkedHashMap<>(correction); invalid.put("name", "x".repeat(161));
        decide(invalid, "resubmit").andExpect(status().isUnprocessableEntity());
        invalid = new LinkedHashMap<>(correction); invalid.put("categoryId", option(LookupType.CATEGORY, "显示器").getId());
        decide(invalid, "resubmit").andExpect(status().isUnprocessableEntity());
        String path = "/api/settings/ad-deployment/intakes/" + externalId + "/resubmit";
        mvc.perform(post(path).with(user(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(correction))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("viewer").roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(correction))).andExpect(status().isForbidden());
        mvc.perform(post(path).secure(true).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(correction))).andExpect(status().isForbidden());
        assertEquals("rejected", intakes.findById(externalId).orElseThrow().state);
        assertEquals(1, intakes.findById(externalId).orElseThrow().revision);
    }
    @Test void concurrentResubmissionsOnlyReopenOneRevision() throws Exception {
        receive(request()).andExpect(status().isAccepted());
        decide(Map.of("revision", 0, "note", "更正名称"), "reject").andExpect(status().isOk());
        var correction = review(); correction.put("revision", 1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1); var tasks = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> { start.await(); return decide(correction, "resubmit").andReturn().getResponse().getStatus(); }));
            start.countDown();
            for (var task : tasks) assertEquals(200, task.get(30, TimeUnit.SECONDS));
        }
        assertEquals(2, intakes.findById(externalId).orElseThrow().revision);
        assertNull(intakes.findById(externalId).orElseThrow().assetId);
    }
    @Test void approvalValidatesCategoryModelRevisionAndDefaultsWithoutChangingWaitingRecord() throws Exception {
        receive(request()).andExpect(status().isAccepted());
        var approval = review(); approval.put("model", "unknown"); decide(approval, "approve").andExpect(status().isUnprocessableEntity());
        approval = review(); approval.put("graphicsCard", "Microsoft Basic Display Adapter");
        decide(approval, "approve").andExpect(status().isUnprocessableEntity());
        approval = review(); approval.put("categoryId", option(LookupType.CATEGORY, "显示器").getId());
        decide(approval, "approve").andExpect(status().isUnprocessableEntity());
        approval = review(); approval.put("revision", 5); decide(approval, "approve").andExpect(status().isConflict());
        var company = option(LookupType.COMPANY, "微丰启"); jdbc.update("update lookup_values set disabled=true where id=?", company.getId());
        try {
            receive(request()).andExpect(status().isAccepted());
            mvc.perform(get(DeploymentSecurityConfig.CONNECTION).secure(true).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            decide(review(), "approve").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEFAULT_DICTIONARY_INVALID"));
        } finally { jdbc.update("update lookup_values set disabled=false where id=?", company.getId()); }
        assertEquals("pending_review", intakes.findById(externalId).orElseThrow().state);
        decide(Map.of("revision", 0, "note", ""), "reject").andExpect(status().isUnprocessableEntity());
    }
    @Test void serviceCredentialsCannotReviewAndBrowserReviewRequiresCsrf() throws Exception {
        receive(request()).andExpect(status().isAccepted());
        String path = "/api/settings/ad-deployment/intakes/" + externalId + "/approve";
        mvc.perform(post(path).secure(true).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(review()))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(review())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/ad-deployment/intakes").with(user("viewer").roles("USER"))).andExpect(status().isForbidden());
        String foreign = credentials.issue("other-service", Instant.now().plusSeconds(3600)).token();
        mvc.perform(get(DeploymentSecurityConfig.INTAKES + "/" + externalId).secure(true).header("Authorization", "Bearer " + foreign))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("pending_review"))
                .andExpect(jsonPath("$.snapshot").doesNotExist());
    }
    @Test void rotatedDedicatedCredentialCanResumeKnownIntakeButRevokedCredentialCannot() throws Exception {
        var body = request(); receive(body).andExpect(status().isAccepted());
        var original = intakes.findById(externalId).orElseThrow();
        var replacement = credentials.issue("replacement-ad-service", Instant.now().plusSeconds(3600));
        credentials.revoke(original.credentialId);
        receive(body).andExpect(status().isUnauthorized());
        token = replacement.token();
        receive(body).andExpect(status().isAccepted());
        assertEquals(original.credentialId, intakes.findById(externalId).orElseThrow().credentialId);
        mvc.perform(get(DeploymentSecurityConfig.INTAKES + "/" + externalId).secure(true).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshot").doesNotExist());
        mvc.perform(get("/api/settings/ad-deployment/intakes").secure(true).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
    @Test void bodyLimitsAndUnknownKeysAreRejected() throws Exception {
        var body = request(); body.put("assetTag", "202611100001"); receive(body).andExpect(status().isUnprocessableEntity());
        mvc.perform(post(DeploymentSecurityConfig.INTAKES).secure(true).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("x".repeat(32769))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post(DeploymentSecurityConfig.INTAKES).secure(true).header("Authorization", "Bearer " + token)
                .contentType(MediaType.TEXT_PLAIN).content("{}" )).andExpect(status().isUnsupportedMediaType());
        properties.setEnabled(false); receive(request()).andExpect(status().isServiceUnavailable());
    }
    @Test void concurrentApprovalsAllocateOnlyOnce() throws Exception {
        receive(request()).andExpect(status().isAccepted()); long before = assets.count();
        var body = review();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1); var tasks = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> { start.await(); return decide(body, "approve").andReturn().getResponse().getStatus(); }));
            start.countDown();
            for (var task : tasks) assertEquals(200, task.get(30, TimeUnit.SECONDS));
        }
        assertEquals(before + 1, assets.count());
    }
    @Test void adApprovalAcceptsOptionalCustodianDepartmentAndDomainWithoutChangingDefaultsWhenOmitted() throws Exception {
        receive(request()).andExpect(status().isAccepted());
        option(LookupType.STATUS, "已经领出");
        var approval=review(); approval.put("assignedTo","张三"); approval.put("ownershipDepartment","运营部"); approval.put("domainJoinStatus","JOINED");
        decide(approval,"approve").andExpect(status().isOk()).andExpect(jsonPath("$.assignedTo").value("张三"))
                .andExpect(jsonPath("$.ownershipDepartment").value("运营部")).andExpect(jsonPath("$.domainJoinStatus").value("JOINED"));
        var asset=assets.findById(intakes.findById(externalId).orElseThrow().assetId).orElseThrow();
        assertTrue(asset.isCheckedOut()); assertEquals("张三",asset.getAssignedTo()); assertNull(asset.getAssignedUserId());
        assertEquals("运营部",asset.getOwnershipDepartment()); assertEquals("已经领出",asset.getStatus().getName());
        assertEquals(DomainJoinStatus.JOINED,asset.getDomainJoinStatus());
    }
    @Test void optionalApprovalFieldsHaveIndependentValidation() throws Exception {
        receive(request()).andExpect(status().isAccepted());
        for(var field:List.of("assignedTo","ownershipDepartment")){
            var approval=review(); approval.put(field,"x".repeat(121)); decide(approval,"approve").andExpect(status().isUnprocessableEntity());
        }
        var approval=review(); approval.put("domainJoinStatus","WRONG"); decide(approval,"approve").andExpect(status().isUnprocessableEntity());
        approval=review(); approval.put("assignedTo","  "); approval.put("ownershipDepartment",""); approval.put("domainJoinStatus","NOT_JOINED");
        decide(approval,"approve").andExpect(status().isOk());
        var asset=assets.findById(intakes.findById(externalId).orElseThrow().assetId).orElseThrow();
        assertFalse(asset.isCheckedOut()); assertNull(asset.getAssignedTo()); assertEquals("当前可用",asset.getStatus().getName());
        assertEquals(DomainJoinStatus.NOT_JOINED,asset.getDomainJoinStatus());
    }
    Map<String,Object> windowsPackage() throws Exception {
        byte[] bytes=mvc.perform(post("/api/settings/windows-collection/packages").secure(true).with(user(admin)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("name","仓库采集","days",7,"baseUrl","https://asset.test"))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
        var files=new HashMap<String,byte[]>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes),java.nio.charset.StandardCharsets.UTF_8)){
            java.util.zip.ZipEntry entry; while((entry=zip.getNextEntry())!=null)files.put(entry.getName(),zip.readAllBytes());
        }
        assertEquals(Set.of("Collect.ps1","一键采集.cmd","config.json"),files.keySet());
        assertEquals((byte)0xef,files.get("Collect.ps1")[0]);
        String launcher=new String(files.get("一键采集.cmd"),java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(launcher.contains("-WindowStyle Hidden"));assertTrue(launcher.contains("-STA"));assertFalse(launcher.contains("pause"));
        var config=json.readValue(files.get("config.json"),Map.class);
        assertEquals("https://asset.test"+DeploymentSecurityConfig.WINDOWS,config.get("endpoint"));
        return new LinkedHashMap<>(config);
    }
    Map<String,Object> windowsBody(String hardware) {
        return Map.of("hardwareUuid",hardware,"name","LENOVO ThinkPad L13","capturedAt","2026-01-01T00:00:00Z","snapshot",request().get("snapshot"));
    }
    ResultActions collect(String credential, Map<String,Object> value) throws Exception {
        return mvc.perform(post(DeploymentSecurityConfig.WINDOWS).secure(true).header("Authorization","Bearer "+credential)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value)));
    }
    @Test void windowsPackagesSubmitOnlyAndRepeatHardwareDoesNotCreateDuplicateOrOverwrite() throws Exception {
        var config=windowsPackage(); String collector=(String)config.get("token");
        String hardware=UUID.randomUUID().toString(); long before=assets.count();
        var response=collect(collector,windowsBody(hardware)).andExpect(status().isAccepted()).andReturn().getResponse();
        externalId=json.readTree(response.getContentAsByteArray()).get("externalRegistrationId").asText();
        String original=intakes.findById(externalId).orElseThrow().requestJson;
        var changed=new LinkedHashMap<>(windowsBody(hardware)); changed.put("name","Changed later");
        collect(collector,changed).andExpect(status().isAccepted());
        assertEquals(original,intakes.findById(externalId).orElseThrow().requestJson); assertEquals(before,assets.count());
        assertEquals("Windows一键采集",intakes.findById(externalId).orElseThrow().source);
        mvc.perform(get(DeploymentSecurityConfig.INTAKES+"/"+externalId).secure(true).header("Authorization","Bearer "+collector)).andExpect(status().isForbidden());
        mvc.perform(post(DeploymentSecurityConfig.INTAKES).secure(true).header("Authorization","Bearer "+collector)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request()))).andExpect(status().isForbidden());
        collect(token,windowsBody(UUID.randomUUID().toString())).andExpect(status().isForbidden());
        decide(review(),"approve").andExpect(status().isOk());
        collect(collector,changed).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("approved"));
        assertEquals(before+1,assets.count());
        String id=collector.substring(4,40);
        mvc.perform(post("/api/settings/windows-collection/packages/"+id+"/revoke").with(user(admin)).with(csrf())).andExpect(status().isOk());
        collect(collector,windowsBody(hardware)).andExpect(status().isUnauthorized());
        assertEquals(before+1,assets.count());
    }
    @Test void collectionPackagePermissionsExpiryAndBodyLimitsAreEnforced() throws Exception {
        mvc.perform(get("/api/settings/windows-collection/packages").with(user("viewer").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/windows-collection/packages").with(user(admin)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        var config=windowsPackage(); String collector=(String)config.get("token");
        collect(collector,windowsBody("00000000-0000-0000-0000-000000000000")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post(DeploymentSecurityConfig.WINDOWS).secure(true).header("Authorization","Bearer "+collector)
            .contentType(MediaType.APPLICATION_JSON).content("x".repeat(32769))).andExpect(status().isPayloadTooLarge());
        var row=credentialRows.findById(collector.substring(4,40)).orElseThrow(); row.expiresAt=Instant.now().minusSeconds(1); credentialRows.saveAndFlush(row);
        collect(collector,windowsBody(UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        assertFalse(credentials.list().stream().anyMatch(v->v.id().equals(row.id)));
        assertTrue(credentials.list(true).stream().anyMatch(v->v.id().equals(row.id)));
    }
    @Test void windowsReportedUserIsReviewReferenceAndCanBeCorrectedOnlyWhilePending() throws Exception {
        String collector=(String)windowsPackage().get("token");
        var body=new LinkedHashMap<>(windowsBody(UUID.randomUUID().toString()));
        body.put("reportedUserName","张三"); body.put("reportedDepartment","运营部");
        var response=collect(collector,body).andExpect(status().isAccepted()).andReturn().getResponse();
        externalId=json.readTree(response.getContentAsByteArray()).get("externalRegistrationId").asText();
        var row=intakes.findById(externalId).orElseThrow();
        assertEquals("张三",row.reportedUserName); assertEquals("运营部",row.reportedDepartment);
        mvc.perform(get("/api/settings/ad-deployment/intakes").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].reportedUserName").value("张三"))
                .andExpect(jsonPath("$.items[0].assignedTo").isEmpty());
        collect(collector,body).andExpect(status().isAccepted());
        assertEquals(0,intakes.findById(externalId).orElseThrow().revision);
        body.put("reportedUserName","李四");
        collect(collector,body).andExpect(status().isAccepted());
        assertEquals(1,intakes.findById(externalId).orElseThrow().revision);
        var approval=review(); approval.put("revision",1);
        decide(approval,"approve").andExpect(status().isOk());
        var asset=assets.findById(intakes.findById(externalId).orElseThrow().assetId).orElseThrow();
        assertFalse(asset.isCheckedOut()); assertNull(asset.getAssignedTo());
        body.put("reportedUserName","王五"); collect(collector,body).andExpect(status().isOk());
        assertEquals("李四",intakes.findById(externalId).orElseThrow().reportedUserName);
        body.put("reportedUserName","x".repeat(121)); collect(collector,body).andExpect(status().isUnprocessableEntity());
    }
    Map<String,Object> monitor(String id,String connection){return Map.of("deviceKey",id,"connection",connection,"brand","Dell","serialNumber","DISPLAY-SN","sizeInches",23.8);}
    Map<String,Object> collection(String platform){return Map.of("platform",platform,"computerType","laptop","status","partial",
        "monitors",List.of(monitor("panel","internal"),monitor("external1","external"),monitor("external2","external")),"mouseBrands",List.of("Logitech"),"keyboardBrands",List.of());}
    void receiveCollected(String platform) throws Exception {
        String collector=(String)windowsPackage().get("token");
        var body=new LinkedHashMap<>(windowsBody(UUID.randomUUID().toString()));body.put("collection",collection(platform));
        var response=collect(collector,body).andExpect(status().isAccepted()).andReturn().getResponse();
        externalId=json.readTree(response.getContentAsByteArray()).get("externalRegistrationId").asText();
    }
    Map<String,Object> displayReview(String key,String brand){return Map.of("deviceKey",key,"brand",brand,"serialNumber","DISPLAY-SN","sizeInches",23.8);}
    @Test void approvesComputerAndSelectedExternalDisplaysExactlyOnceWithSameCustodian() throws Exception {
        receiveCollected("windows");option(LookupType.CATEGORY,"显示器");option(LookupType.STATUS,"已经领出");
        long before=assets.count(); var approval=review();approval.put("assignedTo","张三");approval.put("ownershipDepartment","研发部");
        approval.put("monitors",List.of(displayReview("external1","Dell"),displayReview("external2","Dell")));approval.put("confirmNewMonitors",true);
        var response=decide(approval,"approve").andExpect(status().isOk()).andExpect(jsonPath("$.monitorAssets.length()").value(2))
            .andExpect(jsonPath("$.collection.mouseBrands[0]").value("Logitech")).andReturn().getResponse();
        assertEquals(before+3,assets.count());
        for(var reference:json.readTree(response.getContentAsByteArray()).get("monitorAssets")){
            var display=assets.findById(reference.get("id").asLong()).orElseThrow();
            assertEquals("显示器",display.getCategory().getName());assertEquals("23.8英寸",display.getScreenSize());
            assertEquals("DISPLAY-SN",display.getManufacturerSerialNumber());assertEquals("张三",display.getAssignedTo());assertEquals("研发部",display.getOwnershipDepartment());
        }
        decide(approval,"approve").andExpect(status().isOk());assertEquals(before+3,assets.count());
    }
    @Test void rejectsInternalDisplayUnknownKeyDuplicatesAndUnconfirmedNewMonitorsWithoutCreatingAssets() throws Exception {
        receiveCollected("windows");option(LookupType.CATEGORY,"显示器");long before=assets.count();
        var approval=review();approval.put("confirmNewMonitors",true);
        for(String key:List.of("panel","invented")){approval.put("monitors",List.of(displayReview(key,"Dell")));decide(approval,"approve").andExpect(status().isUnprocessableEntity());}
        approval.put("monitors",List.of(displayReview("external1","Dell"),displayReview("external1","Dell")));decide(approval,"approve").andExpect(status().isUnprocessableEntity());
        approval.put("monitors",List.of(displayReview("external1","Dell")));approval.put("confirmNewMonitors",false);decide(approval,"approve").andExpect(status().isUnprocessableEntity());
        assertEquals(before,assets.count());assertEquals("pending_review",intakes.findById(externalId).orElseThrow().state);
    }
    @Test void monitorCreationFailureRollsBackTheComputerAndKeepsIntakePending() throws Exception {
        receiveCollected("windows");option(LookupType.CATEGORY,"显示器");long before=assets.count();
        var approval=review();approval.put("monitors",List.of(displayReview("external1","BlockedBrand")));approval.put("confirmNewMonitors",true);
        jdbc.execute("ALTER TABLE assets ADD CONSTRAINT test_blocked_display CHECK (name NOT LIKE 'BlockedBrand%')");
        try {decide(approval,"approve").andExpect(status().isInternalServerError());assertEquals(before,assets.count());assertEquals("pending_review",intakes.findById(externalId).orElseThrow().state);}
        finally {jdbc.execute("ALTER TABLE assets DROP CONSTRAINT test_blocked_display");}
    }
    @Test void macPackageUsesSameRestrictedIntakeAndCollectsWithoutChangingLegacyWindowsContract() throws Exception {
        var bytes=mvc.perform(post("/api/settings/windows-collection/packages").secure(true).with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("name","Mac采集","days",7,"baseUrl","https://asset.test","platform","macos"))))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=macos-collection.zip")).andReturn().getResponse().getContentAsByteArray();
        var names=new HashSet<String>();try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes),java.nio.charset.StandardCharsets.UTF_8)){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)names.add(entry.getName());}
        assertEquals(Set.of("Collect.js","一键采集.command","使用说明.txt","config.json"),names);
        receiveCollected("macos");assertTrue(externalId.startsWith("macos:"));assertEquals("macOS一键采集",intakes.findById(externalId).orElseThrow().source);
    }
    @Test void validatesCollectionBoundsAndUnknownFieldsOnTheServer() throws Exception {
        String collector=(String)windowsPackage().get("token");var body=new LinkedHashMap<>(windowsBody(UUID.randomUUID().toString()));
        var details=new LinkedHashMap<>(collection("windows"));details.put("mouseBrands",List.of("x".repeat(81)));body.put("collection",details);
        collect(collector,body).andExpect(status().isUnprocessableEntity());
        details.put("mouseBrands",List.of());details.put("monitors",List.of(monitor("duplicate","external"),monitor("duplicate","external")));collect(collector,body).andExpect(status().isUnprocessableEntity());
        details.put("monitors",List.of());details.put("computerType","laptop-because-no-monitor");collect(collector,body).andExpect(status().isUnprocessableEntity());
    }
    @Test void samePersonCanApproveTwoDifferentComputersWithIndependentAssetNumbers() throws Exception {
        option(LookupType.STATUS,"已经领出");long before=assets.count();var ids=new HashSet<Long>();var tags=new HashSet<String>();
        for(int index=0;index<2;index++){
            receiveCollected("windows");var approval=review();approval.put("assignedTo","同一位员工");approval.put("ownershipDepartment","研发部");
            var response=decide(approval,"approve").andExpect(status().isOk()).andReturn().getResponse();
            var reference=json.readTree(response.getContentAsByteArray()).get("asset");ids.add(reference.get("id").asLong());tags.add(reference.get("assetTag").asText());
        }
        assertEquals(before+2,assets.count());assertEquals(2,ids.size());assertEquals(2,tags.size());
    }
    @Test void listsApprovedHistoricalReviewWithoutNewMonitorFields() throws Exception {
        receive(request()).andExpect(status().isAccepted());decide(review(),"approve").andExpect(status().isOk());
        var row=intakes.findById(externalId).orElseThrow();
        var historical=json.readValue(row.reviewJson,Map.class);historical.remove("monitors");historical.remove("confirmNewMonitors");
        row.reviewJson=json.writeValueAsString(historical);intakes.saveAndFlush(row);
        mvc.perform(get("/api/settings/ad-deployment/intakes").param("state","approved").with(user(admin)))
            .andExpect(status().isOk());
    }
    @Test void collectionPackageCanMatchOnlyOneExactRosterNameDepartment() throws Exception {
        String collector=(String)windowsPackage().get("token"), name="部门匹配"+UUID.randomUUID();
        mvc.perform(post("/api/registration-roster/import").with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("people",List.of(Map.of("name",name,"department","研发部","seat","1"),Map.of("name",name+"空","department","","seat","2")))))).andExpect(status().isOk());
        mvc.perform(post(DeploymentSecurityConfig.PERSON).secure(true).header("Authorization","Bearer "+collector).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("name"," "+name+" ")))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.result").value("matched")).andExpect(jsonPath("$.department").value("研发部")).andExpect(jsonPath("$.people").doesNotExist());
        for(var entry:Map.of(name+"空","empty_department","部门匹配","not_found").entrySet())
            mvc.perform(post(DeploymentSecurityConfig.PERSON).secure(true).header("Authorization","Bearer "+collector).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name",entry.getKey())))).andExpect(status().isOk()).andExpect(jsonPath("$.result").value(entry.getValue())).andExpect(jsonPath("$.department").isEmpty());
        mvc.perform(post(DeploymentSecurityConfig.PERSON).secure(true).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"部门匹配\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get(DeploymentSecurityConfig.PERSON).secure(true).header("Authorization","Bearer "+collector)).andExpect(status().isForbidden());
        mvc.perform(post(DeploymentSecurityConfig.PERSON).secure(true).header("Authorization","Bearer "+collector).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(DeploymentSecurityConfig.PERSON).secure(true).header("Authorization","Bearer "+collector).contentType(MediaType.APPLICATION_JSON).content("x".repeat(32769)))
            .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/settings/windows-collection/packages/"+collector.substring(4,40)+"/revoke").with(user(admin)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post(DeploymentSecurityConfig.PERSON).secure(true).header("Authorization","Bearer "+collector).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"部门匹配\"}"))
            .andExpect(status().isUnauthorized());
    }
}
