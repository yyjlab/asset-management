package com.acme.assetmanagement.security;

import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.user.UserAccount;
import com.acme.assetmanagement.user.UserRepository;
import com.acme.assetmanagement.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dingtalkdb;DB_CLOSE_DELAY=-1",
        "app.dingtalk-login.enabled=true", "app.dingtalk-login.client-id=test-client",
        "app.dingtalk-login.client-secret=test-secret",
        "app.dingtalk-login.redirect-uri=http://asset.wfq.com/api/v1/auth/dingtalk/callback"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DingTalkLoginTest {
    private static final String ROOT = "/api/v1/auth/dingtalk";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DingTalkAccountService accounts;
    @MockitoBean DingTalkClient provider;

    @Test void statusDoesNotExposeCredentials() throws Exception {
        mvc.perform(get(ROOT + "/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.bound").value(false))
                .andExpect(jsonPath("$.clientSecret").doesNotExist()).andExpect(jsonPath("$.clientId").doesNotExist());
    }

    @Test void loginUsesConfiguredRedirectAndRandomSessionState() throws Exception {
        var first = begin();
        var second = begin();
        var url = UriComponentsBuilder.fromUriString(first.getResponse().getRedirectedUrl()).build();
        assertEquals("login.dingtalk.com", url.getHost());
        assertNotEquals(state(first), state(second));
        assertTrue(first.getResponse().getRedirectedUrl().contains("response_type=code"));
        assertFalse(first.getResponse().getRedirectedUrl().contains("test-secret"));
        assertEquals("no-store", first.getResponse().getHeader("Cache-Control"));
    }

    @Test void stateRequiresSameSessionAndIsSingleUse() throws Exception {
        var start = begin();
        mvc.perform(get(ROOT + "/callback").param("state", state(start)).param("code", "good"))
                .andExpect(status().isFound()).andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        mvc.perform(get(ROOT + "/callback").session(session(start)).param("state", "wrong").param("code", "good"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        mvc.perform(get(ROOT + "/callback").session(session(start)).param("state", state(start)).param("code", "good"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        verifyNoInteractions(provider);
    }

    @Test void expiredStateDoesNotCallProvider() throws Exception {
        var start = begin();
        var session = session(start);
        String key = DingTalkController.class.getName() + ".pending";
        session.setAttribute(key, new DingTalkController.Pending(state(start),
                Instant.now().minusSeconds(601).getEpochSecond(), null, null));
        mvc.perform(get(ROOT + "/callback").session(session).param("state", state(start)).param("code", "good"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        verifyNoInteractions(provider);
    }

    @Test void identityWithoutVerifiedEmployeeNameCannotCreateOrEnterAccount() throws Exception {
        long count = users.count();
        when(provider.unionId("good")).thenReturn("unbound");
        var start = begin();
        mvc.perform(get(ROOT + "/callback").session(session(start)).param("state", state(start)).param("authCode", "good"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        assertEquals(count, users.count());
        mvc.perform(get("/api/auth/me").session(session(start))).andExpect(status().isUnauthorized());
    }

    @Test void firstLoginCreatesPinyinEmployeeWithSelfViewAndDoesNotMergeNames() throws Exception {
        var legacy = create("zhangsan");
        legacy.setDisplayName("张三");
        users.saveAndFlush(legacy);
        long count = users.count();
        when(provider.unionId("employee-code")).thenReturn("new-company-employee");
        when(provider.employeeName("new-company-employee")).thenReturn("张三");
        var start = begin();
        var browser = session(start);
        mvc.perform(get(ROOT + "/callback").session(browser).param("state", state(start)).param("code", "employee-code"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("zhangsan2"))
                .andExpect(jsonPath("$.displayName").value("张三"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.dingtalkLogin").value(true))
                .andExpect(jsonPath("$.dataScope").value("SELF"))
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("ASSET_VIEW"));
        assertEquals(count + 1, users.count());
        assertNull(users.findById(legacy.getId()).orElseThrow().getDingtalkIdentity());
        var again = begin();
        mvc.perform(get(ROOT + "/callback").session(session(again)).param("state", state(again)).param("code", "employee-code"))
                .andExpect(redirectedUrl("/"));
        assertEquals(count + 1, users.count());
        verify(provider, times(1)).employeeName("new-company-employee");
        var created = users.findByUsernameIgnoreCase("zhangsan2").orElseThrow();
        created.setEnabled(false); users.saveAndFlush(created);
        var disabled = begin();
        mvc.perform(get(ROOT + "/callback").session(session(disabled)).param("state", state(disabled)).param("code", "employee-code"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        assertFalse(users.findById(created.getId()).orElseThrow().isEnabled());
    }

    @Test void nonMemberCannotProvisionAnAccount() throws Exception {
        long count = users.count();
        when(provider.unionId("outside-code")).thenReturn("outsider");
        when(provider.employeeName("outsider")).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "非本企业员工"));
        var start = begin();
        mvc.perform(get(ROOT + "/callback").session(session(start)).param("state", state(start)).param("code", "outside-code"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        assertEquals(count, users.count());
    }

    @Test void boundUserGetsExistingSessionAndRoleAndReplayIsRejected() throws Exception {
        UserAccount account = create("bound-user");
        accounts.resolve("bound-identity", account.getId(), account.getPasswordHash());
        when(provider.unionId("good")).thenReturn("bound-identity");
        var start = begin();
        MockHttpSession session = session(start);
        String originalSessionId = session.getId();
        mvc.perform(get(ROOT + "/callback").session(session).param("state", state(start)).param("code", "good"))
                .andExpect(redirectedUrl("/"));
        assertNotEquals(originalSessionId, session.getId());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bound-user")).andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.dingtalkLogin").value(true));
        mvc.perform(get(ROOT + "/callback").session(session).param("state", state(start)).param("code", "good"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        verify(provider, times(1)).unionId("good");
    }

    @Test void bindingRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(post(ROOT + "/bind").with(csrf())).andExpect(status().isUnauthorized());
        var account = create("csrf-user");
        var session = passwordLogin(account);
        mvc.perform(post(ROOT + "/bind").session(session)).andExpect(status().isForbidden());
    }

    @Test void passwordAuthenticatedUserCanBindThenUseDingtalk() throws Exception {
        var account = create("binding-user");
        var session = passwordLogin(account);
        mvc.perform(post(ROOT + "/bind").session(session).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authorizationUrl").isString());
        var pending = (DingTalkController.Pending) session.getAttribute(DingTalkController.class.getName() + ".pending");
        when(provider.unionId("binding-code")).thenReturn("new-identity");
        mvc.perform(get(ROOT + "/callback").session(session).param("state", pending.state()).param("code", "binding-code"))
                .andExpect(redirectedUrl("/?dingtalk=bound"));
        assertNotNull(users.findById(account.getId()).orElseThrow().getDingtalkIdentity());
        mvc.perform(get(ROOT + "/status").session(session)).andExpect(jsonPath("$.bound").value(true));
        assertEquals(account.getId(), accounts.resolve("new-identity", null, null).id());
    }

    @Test void passwordLoginDoesNotUseDingtalkMenuFlagEvenForBoundAccount() throws Exception {
        var account = create("bound-password-login");
        accounts.resolve("password-only-identity", account.getId(), account.getPasswordHash());
        var browser = passwordLogin(account);
        mvc.perform(get("/api/auth/me").session(browser))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dingtalkLogin").value(false));
    }

    @Test void dingtalkAdministratorRetainsSettingsAccess() throws Exception {
        var account = create("dingtalk-administrator");
        account.setRole(UserRole.SUPER_ADMIN);
        users.saveAndFlush(account);
        accounts.resolve("administrator-identity", account.getId(), account.getPasswordHash());
        when(provider.unionId("administrator-code")).thenReturn("administrator-identity");
        var start = begin();
        var browser = session(start);
        mvc.perform(get(ROOT + "/callback").session(browser).param("state", state(start)).param("code", "administrator-code"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dingtalkLogin").value(true))
                .andExpect(jsonPath("$.canManageUsers").value(true))
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
        mvc.perform(get("/api/settings/qr").session(browser)).andExpect(status().isOk());
    }

    @Test void successfulPasswordLoginClearsPreviousDingtalkMenuFlag() throws Exception {
        var account = create("switch-login-method");
        accounts.resolve("switch-identity", account.getId(), account.getPasswordHash());
        when(provider.unionId("switch-code")).thenReturn("switch-identity");
        var start = begin();
        var browser = session(start);
        mvc.perform(get(ROOT + "/callback").session(browser).param("state", state(start)).param("code", "switch-code"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(jsonPath("$.dingtalkLogin").value(true));
        mvc.perform(post("/api/auth/login").session(browser).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"switch-login-method\",\"password\":\"TestPassword!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dingtalkLogin").value(false));
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(jsonPath("$.dingtalkLogin").value(false));
    }

    @Test void dingtalkMenuVisibilityDoesNotGrantPermissionsOrExpandAssetScope() throws Exception {
        when(provider.unionId("view-only-code")).thenReturn("view-only-employee");
        when(provider.employeeName("view-only-employee")).thenReturn("测试员工");
        var start = begin();
        var browser = session(start);
        mvc.perform(get(ROOT + "/callback").session(browser).param("state", state(start)).param("code", "view-only-code"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dingtalkLogin").value(true))
                .andExpect(jsonPath("$.canManageUsers").value(false))
                .andExpect(jsonPath("$.dataScope").value("SELF"))
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("ASSET_VIEW"));
        mvc.perform(get("/api/dashboard").session(browser)).andExpect(status().isOk());
        mvc.perform(get("/api/assets").session(browser)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/users").session(browser)).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-logs").session(browser)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/qr").session(browser)).andExpect(status().isForbidden());
        mvc.perform(get("/api/assets/export.csv").session(browser)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/assets/{id}", Long.MAX_VALUE).session(browser).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test void duplicateBindingAndRebindingCannotTakeOverAccounts() {
        var first = create("first");
        var second = create("second");
        accounts.resolve("identity", first.getId(), first.getPasswordHash());
        assertThrows(ApiException.class, () -> accounts.resolve("identity", second.getId(), second.getPasswordHash()));
        assertThrows(ApiException.class, () -> accounts.resolve("another-identity", first.getId(), first.getPasswordHash()));
        assertNull(second.getDingtalkIdentity());
    }

    @Test void disabledAccountsAndChangedPasswordsCannotAuthenticateOrBind() {
        var account = create("disabled");
        accounts.resolve("identity", account.getId(), account.getPasswordHash());
        String before = account.getPasswordHash();
        account.setPasswordHash(encoder.encode("ChangedPassword!"));
        users.saveAndFlush(account);
        assertThrows(ApiException.class, () -> accounts.resolve("identity", account.getId(), before));
        account.setEnabled(false);
        users.saveAndFlush(account);
        assertThrows(ApiException.class, () -> accounts.resolve("identity", null, null));
    }

    @Test void cancelledAuthorizationAndProviderFailureReturnSafeErrors() throws Exception {
        var cancelled = begin();
        mvc.perform(get(ROOT + "/callback").session(session(cancelled)).param("state", state(cancelled)).param("error", "access_denied"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        verifyNoInteractions(provider);
        when(provider.unionId("failed")).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "钉钉身份验证失败"));
        var failed = begin();
        mvc.perform(get(ROOT + "/callback").session(session(failed)).param("state", state(failed)).param("code", "failed"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        mvc.perform(get("/api/auth/me").session(session(failed))).andExpect(status().isUnauthorized());
    }

    private MvcResult begin() throws Exception {
        return mvc.perform(get(ROOT + "/login")).andExpect(status().isFound()).andReturn();
    }
    private String state(MvcResult result) {
        return UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl()).build().getQueryParams().getFirst("state");
    }
    private MockHttpSession session(MvcResult result) { return (MockHttpSession) result.getRequest().getSession(false); }
    private UserAccount create(String username) {
        var account = new UserAccount();
        account.setUsername(username); account.setDisplayName(username);
        account.setPasswordHash(encoder.encode("TestPassword!")); account.setRole(UserRole.USER);
        return users.saveAndFlush(account);
    }
    private MockHttpSession passwordLogin(UserAccount account) throws Exception {
        return session(mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + account.getUsername() + "\",\"password\":\"TestPassword!\"}"))
                .andExpect(status().isOk()).andReturn());
    }
}
