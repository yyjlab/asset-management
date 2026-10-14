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

    @Test void unboundIdentityCannotCreateOrEnterAccount() throws Exception {
        long count = users.count();
        when(provider.unionId("good")).thenReturn("unbound");
        var start = begin();
        mvc.perform(get(ROOT + "/callback").session(session(start)).param("state", state(start)).param("authCode", "good"))
                .andExpect(redirectedUrlPattern("/?dingtalkError=*"));
        assertEquals(count, users.count());
        mvc.perform(get("/api/auth/me").session(session(start))).andExpect(status().isUnauthorized());
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
                .andExpect(jsonPath("$.username").value("bound-user")).andExpect(jsonPath("$.role").value("USER"));
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
