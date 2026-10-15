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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:permissionsdb;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AssetPermissionsTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Autowired LookupRepository lookups;
    @Autowired PasswordEncoder encoder;

    @Test void selfScopeUsesAccountIdNotNamesAndChecksAllReadPaths() throws Exception {
        var employee = employee("scope-owner", Set.of(Permission.ASSET_VIEW));
        Asset own = seed(); own.setAssignedUserId(employee.getId()); own.setAssignedTo("张三");
        own.setImageUrl("/api/public/asset-images/own-test.png"); assets.saveAndFlush(own);
        Asset other = other("960000001001"); other.setAssignedTo("张三");
        other.setImageUrl("/api/public/asset-images/hidden-test.png"); assets.saveAndFlush(other);
        var session = login(employee);
        mvc.perform(get("/api/assets").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(own.getId()));
        mvc.perform(get("/api/assets/{id}", other.getId()).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/assets/{id}/qr", other.getId()).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/assets/{token}", other.getQrToken()).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/asset-images/hidden-test.png").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/assets/{token}", own.getQrToken()).session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/assets/summary").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/assets/export.csv").session(session)).andExpect(status().isForbidden());
        employee.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_EXPORT)); users.saveAndFlush(employee);
        mvc.perform(get("/api/assets/export.csv").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString(own.getAssetTag())))
                .andExpect(content().string(not(containsString(other.getAssetTag()))));
    }

    @Test void operationsAreIndependentFromDashboardAndScope() throws Exception {
        var employee = employee("readonly", Set.of(Permission.ASSET_VIEW));
        Asset own = seed(); own.setAssignedUserId(employee.getId()); assets.saveAndFlush(own);
        var session = login(employee);
        mvc.perform(get("/api/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/assignees").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/assets").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload(own, "研发部", null)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/assets/{id}", own.getId()).session(session).with(csrf()).header("If-Match", own.getVersion())
                .contentType(MediaType.APPLICATION_JSON).content(payload(own, "研发部", null))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/assets/{id}", own.getId()).session(session).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/assets/{id}/return", own.getId()).session(session).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(assets.count())).andExpect(jsonPath("$.assets").doesNotExist())
                .andExpect(content().string(not(containsString(own.getQrToken()))))
                .andExpect(content().string(not(containsString(own.getAssetTag()))));
        employee.setPermissions(Set.of()); users.saveAndFlush(employee);
        mvc.perform(get("/api/assets").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test void adminChangesScopePermissionsAndDisablesExistingSessionsWithoutChangingAssets() throws Exception {
        var employee = employee("access-update", Set.of(Permission.ASSET_VIEW));
        var session = login(employee);
        Asset first = seed(); first.setOwnershipDepartment("研发部"); assets.saveAndFlush(first);
        Asset second = other("960000001002"); second.setOwnershipDepartment("行政部"); assets.saveAndFlush(second);
        long count = assets.count();
        var admin = users.findByUsernameIgnoreCase("admin").orElseThrow();
        String access = "{\"permissions\":[\"ASSET_VIEW\",\"ASSET_EXPORT\"],\"dataScope\":\"DEPARTMENT\",\"departments\":[\"研发部\"],\"enabled\":true}";
        mvc.perform(put("/api/users/{id}/access", employee.getId()).with(user(AccountPrincipal.from(admin))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(access)).andExpect(status().isOk());
        mvc.perform(get("/api/assets").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(first.getId()));
        mvc.perform(get("/api/assets/{id}", second.getId()).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/assets/export.csv").session(session)).andExpect(status().isOk());
        mvc.perform(put("/api/users/{id}/access", employee.getId()).with(user(AccountPrincipal.from(admin))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(access.replace(",\"ASSET_EXPORT\"", ""))).andExpect(status().isOk());
        mvc.perform(get("/api/assets/export.csv").session(session)).andExpect(status().isForbidden());
        mvc.perform(put("/api/users/{id}/access", employee.getId()).with(user(AccountPrincipal.from(admin))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(access.replace("\"enabled\":true", "\"enabled\":false"))).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard").session(session)).andExpect(status().isUnauthorized());
        assertEquals(count, assets.count());
        mvc.perform(put("/api/users/{id}/access", admin.getId()).with(user(AccountPrincipal.from(admin))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(access)).andExpect(status().isBadRequest());
    }

    @Test void assigningAccountIsExplicitAndPreservesUnrelatedData() throws Exception {
        var employee = employee("assignee", Set.of(Permission.ASSET_VIEW));
        var own = seed(); own.setNotes("不能改掉的历史备注"); assets.saveAndFlush(own);
        var admin = users.findByUsernameIgnoreCase("admin").orElseThrow();
        String body = payload(own, "研发部", employee.getId());
        mvc.perform(put("/api/assets/{id}", own.getId()).with(user(AccountPrincipal.from(admin))).with(csrf())
                .header("If-Match", own.getVersion()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedUserId").value(employee.getId())).andExpect(jsonPath("$.assignedTo").value(employee.getDisplayName()));
        assertEquals("不能改掉的历史备注", assets.findById(own.getId()).orElseThrow().getNotes());
        mvc.perform(get("/api/assets").session(login(employee))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        users.delete(employee); users.flush();
        assertEquals("张三", assets.findById(own.getId()).orElseThrow().getAssignedTo());
    }

    @Test void restrictedEditorCannotMoveAssetsOutsideDepartmentOrModifyHiddenBindings() throws Exception {
        var employee = employee("dept-editor", Set.of(Permission.ASSET_VIEW, Permission.ASSET_EDIT, Permission.ASSET_CHECKOUT));
        employee.setDataScope(DataScope.DEPARTMENT); employee.setDepartments(Set.of("研发部")); users.saveAndFlush(employee);
        var own = seed(); own.setOwnershipDepartment("研发部"); assets.saveAndFlush(own);
        var hidden = other("960000001003"); hidden.setOwnershipDepartment("行政部");
        var display = lookups.saveAndFlush(new LookupValue(LookupType.CATEGORY, "权限测试显示器", AssetProfile.DISPLAY));
        hidden.setCategory(display); own.getBoundDisplays().add(hidden); hidden.getBoundComputers().add(own); assets.saveAndFlush(own);
        var session = login(employee);
        mvc.perform(get("/api/assets/{id}", own.getId()).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.boundDisplays").isEmpty());
        mvc.perform(put("/api/assets/{id}", own.getId()).session(session).with(csrf()).header("If-Match", own.getVersion())
                .contentType(MediaType.APPLICATION_JSON).content(payload(own, "行政部", null))).andExpect(status().isForbidden());
        assertEquals("研发部", assets.findById(own.getId()).orElseThrow().getOwnershipDepartment());
        mvc.perform(put("/api/assets/{id}", own.getId()).session(session).with(csrf()).header("If-Match", own.getVersion())
                .contentType(MediaType.APPLICATION_JSON).content(payload(own, "研发部", null))).andExpect(status().isNotFound());
        assertTrue(assets.findById(own.getId()).orElseThrow().getBoundDisplays().contains(hidden));
    }

    @Test void importsRequireBothPermissionAndUnrestrictedScope() throws Exception {
        var employee = employee("import-operator", Set.of(Permission.ASSET_VIEW, Permission.ASSET_CREATE,
                Permission.ASSET_CHECKOUT, Permission.ASSET_IMPORT));
        var session = login(employee);
        var file = new org.springframework.mock.web.MockMultipartFile("file", "assets.csv", "text/csv", (
                "资产编号*,资产名称*,所属公司*,设备型号*,资产分类*,资产状态*,存放位置*\n" +
                "960000002001,导入测试,测试公司,测试型号,台式机,当前可用,仓库\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long count = assets.count();
        mvc.perform(multipart("/api/assets/import/preview").file(file).session(session).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/assets/import/commit").file(file).session(session).with(csrf())).andExpect(status().isForbidden());
        employee.setDataScope(DataScope.ALL); users.saveAndFlush(employee);
        mvc.perform(multipart("/api/assets/import/preview").file(file).session(session).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canImport").value(true));
        employee.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_CREATE)); users.saveAndFlush(employee);
        mvc.perform(multipart("/api/assets/import/commit").file(file).session(session).with(csrf())).andExpect(status().isForbidden());
        assertEquals(count, assets.count());
    }

    @Test void checkoutPermissionAndSourceScopeCannotBeBypassedByEditingOrCloning() throws Exception {
        var employee = employee("cannot-assign", Set.of(Permission.ASSET_VIEW, Permission.ASSET_EDIT, Permission.ASSET_CREATE));
        var own = seed(); own.setAssignedUserId(employee.getId()); assets.saveAndFlush(own);
        var hidden = other("960000002002");
        var session = login(employee);
        long count = assets.count();
        mvc.perform(put("/api/assets/{id}", own.getId()).session(session).with(csrf()).header("If-Match", own.getVersion())
                .contentType(MediaType.APPLICATION_JSON).content(payload(own, "研发部", employee.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/assets").param("cloneSourceId", hidden.getId().toString()).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload(hidden, "研发部", employee.getId())))
                .andExpect(status().isNotFound());
        assertEquals(count, assets.count());
    }

    @Test void editAndCheckoutCannotBypassReturnPermission() throws Exception {
        var employee = employee("return-boundary", Set.of(Permission.ASSET_VIEW, Permission.ASSET_EDIT, Permission.ASSET_CHECKOUT));
        employee.setDataScope(DataScope.ALL); users.saveAndFlush(employee);
        var own = seed(); var available = own.getStatus();
        var checkedOut = lookups.findByTypeOrderByNameAsc(LookupType.STATUS).stream()
                .filter(l -> l.getName().contains("领出") || l.getName().contains("在用")).findFirst().orElseThrow();
        own.setCheckedOut(true); own.setStatus(checkedOut); own.setAssignedUserId(employee.getId()); own.setAssignedTo(employee.getDisplayName());
        assets.saveAndFlush(own);
        var session = login(employee);
        String body = payload(own, "研发部", null).replace("\"statusId\":" + checkedOut.getId(), "\"statusId\":" + available.getId())
                .replace("\"checkedOut\":true", "\"checkedOut\":false");
        mvc.perform(put("/api/assets/{id}", own.getId()).session(session).with(csrf()).header("If-Match", own.getVersion())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertTrue(assets.findById(own.getId()).orElseThrow().isCheckedOut());
        employee.setPermissions(Set.of(Permission.ASSET_VIEW, Permission.ASSET_EDIT, Permission.ASSET_RETURN)); users.saveAndFlush(employee);
        mvc.perform(put("/api/assets/{id}", own.getId()).session(session).with(csrf()).header("If-Match", own.getVersion())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.checkedOut").value(false));
    }

    private UserAccount employee(String username, Set<Permission> permissions) {
        var account = new UserAccount(); account.setUsername(username); account.setDisplayName("张三");
        account.setPasswordHash(encoder.encode("TestPassword123!")); account.setPermissions(permissions);
        account.setAccessConfigured(true); account.setDataScope(DataScope.SELF);
        return users.saveAndFlush(account);
    }
    private MockHttpSession login(UserAccount account) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + account.getUsername() + "\",\"password\":\"TestPassword123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    private Asset seed() { return assets.findByAssetTagIgnoreCase("202609010001").orElseThrow(); }
    private Asset other(String tag) {
        var seed = seed(); var a = new Asset(); a.setAssetTag(tag); a.setName("其他员工资产");
        a.setCompany(seed.getCompany()); a.setModel(seed.getModel()); a.setCategory(seed.getCategory());
        a.setStatus(seed.getStatus()); a.setLocation(seed.getLocation()); return assets.saveAndFlush(a);
    }
    private String payload(Asset asset, String department, Long assignee) {
        var status = lookups.findByTypeOrderByNameAsc(LookupType.STATUS).stream()
                .filter(l -> l.getName().contains("领出") || l.getName().contains("在用")).findFirst().orElseThrow();
        return """
                {"assetTag":"%s","name":"%s","ownershipDepartment":"%s","companyId":%d,"modelId":%d,
                 "categoryId":%d,"statusId":%d,"locationId":%d,"checkedOut":true,"assignedTo":"张三",
                 "assignedUserId":%s,"notes":"不能改掉的历史备注"}
                """.formatted(asset.getAssetTag(), asset.getName(), department, asset.getCompany().getId(), asset.getModel().getId(),
                asset.getCategory().getId(), status.getId(), asset.getLocation().getId(), assignee);
    }
}
