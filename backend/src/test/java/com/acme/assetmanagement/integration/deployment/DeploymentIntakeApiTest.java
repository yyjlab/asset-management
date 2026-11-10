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
}
