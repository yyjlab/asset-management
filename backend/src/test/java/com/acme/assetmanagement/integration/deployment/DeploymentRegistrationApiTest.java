package com.acme.assetmanagement.integration.deployment;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.audit.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ad-deployment-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "app.ad-deployment.enabled=true", "app.asset-images.directory=${java.io.tmpdir}/asset-deployment-test-images"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeploymentRegistrationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AssetRepository assets;
    @Autowired LookupRepository lookups;
    @Autowired DeploymentCredentialService credentialService;
    @Autowired DeploymentCredentialRepository credentials;
    @Autowired DeploymentRegistrationRepository registrations;
    @Autowired AuditLogRepository audits;
    @Autowired DeploymentProperties properties;
    @Autowired com.acme.assetmanagement.setting.SystemSettingRepository settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager manager;
    String token;
    String credentialId;
    long categoryId;
    long companyId;
    long locationId;
    String tag;
    String externalId;
    static final Instant CAPTURED = Instant.parse("2026-01-01T00:00:00Z");
    @BeforeEach void prepare() {
        settings.deleteById(DeploymentAccessService.KEY);
        properties.setEnabled(true);
        companyId = option(LookupType.COMPANY, "微丰启").getId();
        locationId = option(LookupType.LOCATION, "公司").getId();
        option(LookupType.STATUS, "当前可用");
        categoryId = option(LookupType.CATEGORY, "笔记本电脑").getId();
        var issued = credentialService.issue("test-receiver", Instant.now().plusSeconds(3600));
        token = issued.token(); credentialId = issued.credential().id();
        tag = "00" + String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        externalId = UUID.randomUUID().toString();
    }
    private LookupValue option(LookupType type, String name) {
        return lookups.findByTypeAndNameIgnoreCase(type, name).orElseGet(() -> lookups.saveAndFlush(new LookupValue(type, name)));
    }
    private Map<String, Object> request() {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("model", "ThinkPad Deployment Test"); snapshot.put("cpu", "Intel Core i5-1235U");
        snapshot.put("memory", "16 GB"); snapshot.put("manufacturerSerialNumber", "Shared-Firmware-SN");
        snapshot.put("internalDisks", List.of(Map.of("model", "Internal SSD", "capacityBytes", 512L * 1073741824L, "busType", "NVMe")));
        snapshot.put("graphicsCards", List.of("Intel Iris Xe Graphics"));
        var root = new LinkedHashMap<String, Object>(); root.put("externalRegistrationId", externalId); root.put("assetTag", tag);
        root.put("categoryId", categoryId); root.put("capturedAt", CAPTURED.toString());
        root.put("operator", Map.of("id", "ad-admin-001", "displayName", "装机管理员")); root.put("snapshot", snapshot);
        return root;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> snapshot(Map<String, Object> root) { return (Map<String, Object>)root.get("snapshot"); }
    private ResultActions send(Map<String, Object> body) throws Exception { return send(body, token); }
    private ResultActions send(Map<String, Object> body, String bearer) throws Exception {
        return mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }
    private long created(Map<String, Object> body) throws Exception {
        var result = send(body).andExpect(status().isCreated()).andReturn();
        return ((Number)((Map<?, ?>)json.readValue(result.getResponse().getContentAsByteArray(), Map.class).get("asset")).get("id")).longValue();
    }
    private long auditCount(String key) {
        return audits.findAll().stream().filter(a -> a.getAction() == AuditAction.AD_DEPLOYMENT_REGISTER
                && a.getChangesJson().contains(key)).count();
    }

    @Test void createsBeforeInstallationWithExactTagAndSafeDefaults() throws Exception {
        var body = request(); long id = created(body);
        var asset = assets.findById(id).orElseThrow();
        assertEquals(tag, asset.getAssetTag()); assertTrue(tag.startsWith("00"));
        assertEquals(companyId, asset.getCompany().getId()); assertEquals(locationId, asset.getLocation().getId());
        assertEquals("当前可用", asset.getStatus().getName()); assertFalse(asset.isCheckedOut());
        assertNull(asset.getAssignedTo()); assertNull(asset.getAssignedUserId()); assertNull(asset.getDomainJoinStatus());
        assertNull(asset.getPurchasePrice()); assertNull(asset.getOrderNumber());
        assertEquals("Shared-Firmware-SN", asset.getManufacturerSerialNumber());
        assertEquals("Internal SSD 512 GiB (NVMe)", asset.getStorage());
        var audit = audits.findAll().stream().filter(a -> a.getAction() == AuditAction.AD_DEPLOYMENT_REGISTER
                && a.getChangesJson().contains(externalId)).findFirst().orElseThrow();
        assertEquals("INTEGRATION", audit.getActorRole()); assertEquals("ad-deployment:" + credentialId, audit.getActorUsername());
        assertNull(audit.getActorUserId()); assertTrue(audit.getChangesJson().contains("装机管理员"));
        assertTrue(audit.getChangesJson().contains("AD 装机平台")); assertFalse(audit.getChangesJson().contains(token));
    }
    @Test void samePayloadReplaysAndDoesNotDuplicateCreationOrAudit() throws Exception {
        var body = request(); long id = created(body);
        send(body).andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(true)).andExpect(jsonPath("$.asset.id").value(id));
        assertEquals(1, auditCount(externalId));
    }
    @Test void credentialRotationRetainsIdempotency() throws Exception {
        var body = request(); long id = created(body);
        var replacement = credentialService.issue("rotated", Instant.now().plusSeconds(3600));
        credentialService.revoke(credentialId);
        send(body, replacement.token()).andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.asset.id").value(id));
    }
    @Test void changedContentUnderSameExternalIdIsRejected() throws Exception {
        var body = request(); created(body); snapshot(body).put("cpu", "Different CPU");
        send(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, auditCount(externalId));
    }
    @Test void changedOperatorOrAssetTagCannotReuseExternalId() throws Exception {
        var body = request(); created(body); body.put("operator", Map.of("id", "another", "displayName", "另一个人"));
        send(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        body = request(); body.put("assetTag", "001234567899");
        send(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertTrue(assets.findByAssetTagIgnoreCase("001234567899").isEmpty());
    }
    @Test void newIdSameConfigurationReturnsExistingDespiteDifferentSerial() throws Exception {
        var body = request(); long id = created(body); body.put("externalRegistrationId", UUID.randomUUID().toString());
        snapshot(body).put("manufacturerSerialNumber", "A-different-SN");
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("already_exists"))
                .andExpect(jsonPath("$.asset.id").value(id));
        assertEquals("Shared-Firmware-SN", assets.findById(id).orElseThrow().getManufacturerSerialNumber());
    }
    @Test void duplicateSerialAcrossDifferentAssetTagsDoesNotBlockCreate() throws Exception {
        var first = request(); long id = created(first);
        var second = request(); second.put("externalRegistrationId", UUID.randomUUID().toString());
        second.put("assetTag", "01" + tag.substring(2)); long secondId = created(second);
        assertNotEquals(id, secondId);
        assertEquals(assets.findById(id).orElseThrow().getManufacturerSerialNumber(), assets.findById(secondId).orElseThrow().getManufacturerSerialNumber());
    }
    @Test void conflictReturnsDifferencesAndPreservesAllExistingFields() throws Exception {
        var body = request(); long id = created(body);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var asset = assets.findById(id).orElseThrow(); asset.setCpu("Existing CPU"); asset.setCheckedOut(true);
            asset.setAssignedTo("在用员工"); asset.setAssignedUserId(12345L); asset.setPurchasePrice(new BigDecimal("8999.00"));
            asset.setOrderNumber("DO-NOT-CHANGE"); asset.setDomainJoinStatus(DomainJoinStatus.JOINED);
            asset.setNotes("keep-notes"); asset.setStatus(option(LookupType.STATUS, "已报废"));
            asset.setCompany(option(LookupType.COMPANY, "Other company")); asset.setLocation(option(LookupType.LOCATION, "Other location"));
            asset.setImageUrl("https://images.test/keep.jpg"); assets.saveAndFlush(asset);
        });
        long version = assets.findById(id).orElseThrow().getVersion();
        body.put("externalRegistrationId", UUID.randomUUID().toString());
        send(body).andExpect(status().isConflict()).andExpect(jsonPath("$.result").value("configuration_conflict"))
                .andExpect(jsonPath("$.differences[0].field").value("cpu"));
        var unchanged = assets.findById(id).orElseThrow();
        assertEquals(version, unchanged.getVersion()); assertEquals("Existing CPU", unchanged.getCpu());
        assertEquals("在用员工", unchanged.getAssignedTo()); assertEquals(12345L, unchanged.getAssignedUserId());
        assertTrue(unchanged.isCheckedOut()); assertEquals("已报废", unchanged.getStatus().getName());
        assertEquals("Other company", unchanged.getCompany().getName()); assertEquals("Other location", unchanged.getLocation().getName());
        assertEquals(new BigDecimal("8999.00"), unchanged.getPurchasePrice()); assertEquals("DO-NOT-CHANGE", unchanged.getOrderNumber());
        assertEquals("keep-notes", unchanged.getNotes()); assertEquals(DomainJoinStatus.JOINED, unchanged.getDomainJoinStatus());
        assertEquals("https://images.test/keep.jpg", unchanged.getImageUrl());
        send(body).andExpect(status().isConflict()).andExpect(jsonPath("$.replayed").value(true));
    }
    @Test void missingDataIsNotInventedAndBasicDriverIsNotARealGpu() throws Exception {
        var body = request(); snapshot(body).put("cpu", null); snapshot(body).put("memory", null);
        snapshot(body).put("internalDisks", List.of()); snapshot(body).put("graphicsCards", List.of("Microsoft Basic Display Adapter", "Microsoft 基本显示适配器"));
        var result = send(body).andExpect(status().isCreated()).andExpect(jsonPath("$.warnings[0]").value("BASIC_DISPLAY_DRIVER_IGNORED")).andReturn();
        var asset = assets.findByAssetTagIgnoreCase(tag).orElseThrow();
        assertNull(asset.getCpu()); assertNull(asset.getMemory()); assertNull(asset.getStorage()); assertNull(asset.getGraphicsCard());
    }
    @Test void missingOrGenericModelIsRejectedWithoutClaimingIdempotencyKey() throws Exception {
        var body = request(); snapshot(body).remove("model");
        send(body).andExpect(status().isUnprocessableEntity()); assertFalse(registrations.existsById(externalId));
        snapshot(body).put("model", "System Product Name"); send(body).andExpect(status().isUnprocessableEntity());
        snapshot(body).put("model", "Actual model"); created(body);
    }
    @Test void numericUnicodeAndWhitespaceAssetTagsAreRejected() throws Exception {
        for (Object bad : List.of(123456789012L, "１２３４５６７８９０１２", " 123456789012", "123")) {
            var body = request(); body.put("assetTag", bad);
            send(body).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertFalse(registrations.existsById(externalId));
    }
    @Test void usbDisksAndUnknownFieldsAreRejected() throws Exception {
        var body = request(); snapshot(body).put("internalDisks", List.of(Map.of("capacityBytes", 64000000000L, "busType", "USB")));
        send(body).andExpect(status().isUnprocessableEntity());
        body = request(); body.put("assignedTo", "不要自动领用"); send(body).andExpect(status().isUnprocessableEntity());
        body = request(); snapshot(body).put("domainJoinStatus", "JOINED"); send(body).andExpect(status().isUnprocessableEntity());
    }
    @Test void missingDefaultDictionaryIsExplicitAndDoesNotAutoCreate() throws Exception {
        var body = request(); var company = lookups.findById(companyId).orElseThrow();
        company.setDisabled(true); lookups.saveAndFlush(company);
        try {
            send(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEFAULT_DICTIONARY_INVALID"));
            assertTrue(assets.findByAssetTagIgnoreCase(tag).isEmpty()); assertFalse(registrations.existsById(externalId));
        } finally { company.setDisabled(false); lookups.saveAndFlush(company); }
    }
    @Test void ambiguousDefaultDictionaryIsRejectedRatherThanChoosingOne() throws Exception {
        var duplicate = lookups.saveAndFlush(new LookupValue(LookupType.LOCATION, " 公司 "));
        try { send(request()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEFAULT_DICTIONARY_INVALID")); }
        finally { lookups.deleteById(duplicate.getId()); }
    }
    @Test void nonComputerCategoryIsRejected() throws Exception {
        var body = request(); body.put("categoryId", option(LookupType.CATEGORY, "显示器").getId());
        send(body).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
    }
    @Test void leftoverSerialUniqueIndexFailsClosedWithoutAutomaticMigration() throws Exception {
        String serial = "unique-" + UUID.randomUUID(); var body = request(); snapshot(body).put("manufacturerSerialNumber", serial);
        // Other tests intentionally retain duplicate SNs. Build this simulated legacy index over a clean column.
        var originals = jdbc.queryForList("select id, manufacturer_serial_number from assets where manufacturer_serial_number is not null");
        jdbc.update("update assets set manufacturer_serial_number = null");
        jdbc.execute("create unique index test_legacy_unique_sn on assets(manufacturer_serial_number)");
        try {
            send(body).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SN_SCHEMA_MIGRATION_REQUIRED"));
            assertTrue(assets.findByAssetTagIgnoreCase(tag).isEmpty());
        } finally {
            jdbc.execute("drop index test_legacy_unique_sn");
            for (var original : originals) jdbc.update("update assets set manufacturer_serial_number = ? where id = ?",
                    original.get("MANUFACTURER_SERIAL_NUMBER"), original.get("ID"));
        }
    }
    @Test void revokedExpiredWrongAndMissingCredentialsAreRejected() throws Exception {
        var body = request(); send(body, token + "wrong").andExpect(status().isUnauthorized());
        mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isUnauthorized());
        var row = credentials.findById(credentialId).orElseThrow(); row.expiresAt = Instant.now().minusSeconds(1); credentials.saveAndFlush(row);
        send(body).andExpect(status().isUnauthorized());
        row.expiresAt = Instant.now().plusSeconds(3600); credentials.saveAndFlush(row); credentialService.revoke(credentialId);
        send(body).andExpect(status().isUnauthorized());
    }
    @Test void browserSessionCannotSubstituteForDedicatedCredentialAndCredentialCannotReadAssets() throws Exception {
        mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request()))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assets").secure(true).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/settings/ad-deployment/credentials").secure(true).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(delete(DeploymentSecurityConfig.PATH).secure(true).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void insecureTransportDisabledIntegrationAndLargeRequestsAreRejected() throws Exception {
        mvc.perform(post(DeploymentSecurityConfig.PATH).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HTTPS_REQUIRED"));
        properties.setEnabled(false);
        try { send(request()).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INTEGRATION_DISABLED")); }
        finally { properties.setEnabled(true); }
        mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(" ".repeat(32769))).andExpect(status().isPayloadTooLarge());
    }
    @Test void malformedJsonAndWrongContentTypeFailWithoutLeakingRequest() throws Exception {
        mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).header("Authorization", "Bearer " + token)
                .contentType(MediaType.TEXT_PLAIN).content("invalid")).andExpect(status().isUnsupportedMediaType());
    }
    @Test void deletedAssetIsNeverRecreatedByReplay() throws Exception {
        var body = request(); long id = created(body); assets.deleteById(id);
        send(body).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ASSET_NO_LONGER_EXISTS"));
        assertTrue(assets.findByAssetTagIgnoreCase(tag).isEmpty());
    }
    @Test void concurrentRetriesCreateOnlyOneAssetAndOneJournal() throws Exception {
        var body = request(); byte[] payload = json.writeValueAsBytes(body);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var ready = new CountDownLatch(4); var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<MvcResult>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            var ids = new HashSet<Object>();
            for (var future : futures) {
                var result = future.get(30, TimeUnit.SECONDS); assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
                ids.add(((Map<?, ?>)json.readValue(result.getResponse().getContentAsByteArray(), Map.class).get("asset")).get("id"));
            }
            assertEquals(1, ids.size()); assertEquals(1, auditCount(externalId));
        }
    }
    @Test void credentialManagementIsAdminAndCsrfProtectedAndNeverListsSecret() throws Exception {
        var admin = AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
        String body = json.writeValueAsString(Map.of("name", "controller-test", "expiresAt", Instant.now().plusSeconds(3600).toString()));
        mvc.perform(post("/api/settings/ad-deployment/credentials").with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/ad-deployment/credentials").with(user("normal").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        var issued = mvc.perform(post("/api/settings/ad-deployment/credentials").secure(true).with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        var parsed = json.readValue(issued.getResponse().getContentAsByteArray(), Map.class);
        String rawToken = (String)parsed.get("token"); assertNotNull(rawToken);
        String id = (String)((Map<?, ?>)parsed.get("credential")).get("id");
        assertNotEquals(rawToken, credentials.findById(id).orElseThrow().secretHash);
        var listed = mvc.perform(get("/api/settings/ad-deployment/credentials").with(user(admin))).andExpect(status().isOk()).andReturn();
        assertFalse(listed.getResponse().getContentAsString().contains(rawToken));
        mvc.perform(delete("/api/settings/ad-deployment/credentials/{id}", id).with(user(admin)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertFalse(audits.findAll().stream().anyMatch(a -> a.getChangesJson() != null && a.getChangesJson().contains(rawToken)));
    }

    @Test void managementShowsDisabledStatusWithoutClaimingConnectivity() throws Exception {
        var admin = AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
        properties.setEnabled(false);
        mvc.perform(get("/api/settings/ad-deployment/status").with(user(admin)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.secureRequest").value(false))
                .andExpect(jsonPath("$.endpoint").value(DeploymentSecurityConfig.PATH));
        mvc.perform(get("/api/settings/ad-deployment/status").secure(true).with(user(admin)))
                .andExpect(jsonPath("$.secureRequest").value(true));
    }

    private ResultActions updateAccess(boolean enabled, boolean allowHttp, boolean confirmed) throws Exception {
        var admin = AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
        return mvc.perform(put("/api/settings/ad-deployment/settings").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "enabled", enabled, "allowHttp", allowHttp, "acknowledgeHttpRisk", confirmed))));
    }

    @Test void webpageCanEnableAndDisableWithoutEnvironmentChanges() throws Exception {
        properties.setEnabled(false);
        updateAccess(true, false, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.allowHttp").value(false));
        var body = request(); created(body);
        updateAccess(false, false, false).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        properties.setEnabled(true);
        send(body).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INTEGRATION_DISABLED"));
        assertTrue(registrations.existsById(externalId));
        assertNotNull(credentials.findById(credentialId).orElseThrow());
    }

    @Test void internalHttpRequiresExplicitConfirmationAndStillRequiresDedicatedCredential() throws Exception {
        updateAccess(true, true, false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HTTP_RISK_CONFIRMATION_REQUIRED"));
        assertFalse(settings.existsById(DeploymentAccessService.KEY));
        updateAccess(true, true, true).andExpect(status().isOk()).andExpect(jsonPath("$.allowHttp").value(true));
        byte[] body = json.writeValueAsBytes(request());
        mvc.perform(post(DeploymentSecurityConfig.PATH).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(DeploymentSecurityConfig.PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        var admin = AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
        mvc.perform(post("/api/settings/ad-deployment/credentials").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("name", "internal-http",
                        "expiresAt", Instant.now().plusSeconds(3600).toString())))).andExpect(status().isCreated());
        updateAccess(true, false, false).andExpect(status().isOk());
        mvc.perform(post(DeploymentSecurityConfig.PATH).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        send(request()).andExpect(status().isCreated());
    }

    @Test void accessSettingRequiresSuperAdminAndCsrfAndWritesAudit() throws Exception {
        var admin = AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
        String body = "{\"enabled\":true,\"allowHttp\":false,\"acknowledgeHttpRisk\":false}";
        mvc.perform(put("/api/settings/ad-deployment/settings").with(user(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/ad-deployment/settings").with(user("normal").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/ad-deployment/settings").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        updateAccess(true, true, true).andExpect(status().isOk());
        assertEquals("INTERNAL_HTTP", settings.findById(DeploymentAccessService.KEY).orElseThrow().getValue());
        assertTrue(audits.findAll().stream().anyMatch(a -> "AD_DEPLOYMENT_ACCESS".equals(a.getTargetType())
                && a.getAction() == AuditAction.SETTING_UPDATE));
    }

    @Test void managementRejectsNonAdminsAndServiceTokens() throws Exception {
        for (String path : List.of("/api/settings/ad-deployment/status", "/api/settings/ad-deployment/registrations")) {
            mvc.perform(get(path).with(user("normal").roles("USER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).secure(true).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test void historyShowsStoredConflictsAndDeclaredOperatorWithoutSecretsOrHardwareSnapshot() throws Exception {
        var body = request(); created(body);
        String conflictKey = UUID.randomUUID().toString(); body.put("externalRegistrationId", conflictKey);
        snapshot(body).put("cpu", "Different CPU"); send(body).andExpect(status().isConflict());
        var admin = AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
        var response = mvc.perform(get("/api/settings/ad-deployment/registrations").with(user(admin)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page").value(0)).andReturn().getResponse();
        var parsed = json.readTree(response.getContentAsByteArray());
        var entry = parsed.get("items").values().stream().filter(item -> item.get("externalRegistrationId").asString().equals(conflictKey)).findFirst().orElseThrow();
        assertEquals("configuration_conflict", entry.get("result").asString());
        assertEquals("ad-admin-001", entry.get("declaredOperator").get("id").asString());
        assertEquals(tag, entry.get("assetTag").asString());
        assertFalse(response.getContentAsString().contains(token));
        assertFalse(response.getContentAsString().contains("secretHash"));
        assertFalse(response.getContentAsString().contains("Shared-Firmware-SN"));
        assertEquals(1, entry.get("differences").size());
    }

    @Test void historyBoundsPagination() throws Exception {
        var admin = AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
        for (int page : List.of(-1, 100001))
            mvc.perform(get("/api/settings/ad-deployment/registrations").param("page", String.valueOf(page)).with(user(admin)))
                    .andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings/ad-deployment/registrations").param("page", "100000").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void differentCredentialsAndExternalIdsCannotCreateSameAssetTwice() throws Exception {
        String otherToken = credentialService.issue("second-server", Instant.now().plusSeconds(3600)).token();
        var bodies = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 4; i++) { var body = request(); body.put("externalRegistrationId", UUID.randomUUID().toString()); bodies.add(body); }
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<MvcResult>>();
            for (int i = 0; i < 4; i++) {
                byte[] payload = json.writeValueAsBytes(bodies.get(i)); String bearer = i % 2 == 0 ? token : otherToken;
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).header("Authorization", "Bearer " + bearer)
                            .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
                }));
            }
            start.countDown(); int created = 0; var ids = new HashSet<Object>();
            for (var future : futures) {
                var response = future.get(30, TimeUnit.SECONDS).getResponse();
                assertTrue(response.getStatus() == 200 || response.getStatus() == 201, response.getContentAsString());
                if (response.getStatus() == 201) created++;
                ids.add(((Map<?, ?>)json.readValue(response.getContentAsByteArray(), Map.class).get("asset")).get("id"));
            }
            assertEquals(1, created); assertEquals(1, ids.size());
            for (var body : bodies) assertTrue(registrations.existsById((String)body.get("externalRegistrationId")));
        }
    }
    @Test void concurrentDifferentPayloadsWithSameKeyCannotOverwriteWinner() throws Exception {
        var first = request(); var second = request(); snapshot(second).put("cpu", "Another physical CPU");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<Integer>>();
            for (var body : List.of(first, second)) {
                byte[] payload = json.writeValueAsBytes(body);
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown(); var statuses = new ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(30, TimeUnit.SECONDS));
            Collections.sort(statuses); assertEquals(List.of(201, 409), statuses); assertEquals(1, auditCount(externalId));
        }
    }
    @Test void actualMissingDictionaryDoesNotCreateFallbackRecord() throws Exception {
        String temporaryName = "temporarily-hidden-" + UUID.randomUUID();
        jdbc.update("update lookup_values set name = ? where id = ?", temporaryName, locationId);
        try {
            long before = lookups.count();
            send(request()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEFAULT_DICTIONARY_INVALID"));
            assertEquals(before, lookups.count()); assertTrue(assets.findByAssetTagIgnoreCase(tag).isEmpty());
        } finally { jdbc.update("update lookup_values set name = ? where id = ?", "公司", locationId); }
    }
    @Test void matchingConfigurationDoesNotReleaseAnExistingCustodianOrRepairState() throws Exception {
        var body = request(); long id = created(body);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var asset = assets.findById(id).orElseThrow(); asset.setStatus(option(LookupType.STATUS, "维修中"));
            asset.setCheckedOut(true); asset.setAssignedTo("保留员工"); asset.setAssignedUserId(987L);
            asset.setCurrentValue(new BigDecimal("300.00")); assets.saveAndFlush(asset);
        });
        long version = assets.findById(id).orElseThrow().getVersion();
        body.put("externalRegistrationId", UUID.randomUUID().toString());
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("already_exists"));
        var unchanged = assets.findById(id).orElseThrow(); assertEquals(version, unchanged.getVersion());
        assertEquals("维修中", unchanged.getStatus().getName()); assertTrue(unchanged.isCheckedOut());
        assertEquals(987L, unchanged.getAssignedUserId()); assertEquals("保留员工", unchanged.getAssignedTo());
        assertEquals(new BigDecimal("300.00"), unchanged.getCurrentValue());
    }
    @Test void rejectsOverlongFieldsDuplicateJsonKeysAndNonIntegralSizes() throws Exception {
        var body = request(); snapshot(body).put("cpu", "x".repeat(201)); send(body).andExpect(status().isUnprocessableEntity());
        body = request(); snapshot(body).put("internalDisks", List.of(Map.of("capacityBytes", 1.5, "busType", "NVMe")));
        send(body).andExpect(status().isUnprocessableEntity());
        String normal = json.writeValueAsString(request());
        String duplicate = normal.replaceFirst("\\{", "{\"assetTag\":\"111111111111\",");
        mvc.perform(post(DeploymentSecurityConfig.PATH).secure(true).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }
    @Test void credentialIssuanceRequiresHttpsAndBoundedExpiry() throws Exception {
        var admin = AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
        String body = json.writeValueAsString(Map.of("name", "controller-test", "expiresAt", Instant.now().plusSeconds(3600).toString()));
        mvc.perform(post("/api/settings/ad-deployment/credentials").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HTTPS_REQUIRED"));
        assertThrows(DeploymentApiException.class, () -> credentialService.issue("too-long", Instant.now().plusSeconds(367L * 86400)));
    }
}
