package com.acme.assetmanagement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import com.acme.assetmanagement.user.UserRepository;
import com.acme.assetmanagement.user.UserRole;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:authdb;DB_CLOSE_DELAY=-1",
        "asset.admin.username=admin",
        "asset.admin.password=ChangeMe123!"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAuthTest {
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void blocksAnonymousApiAndAuthenticatesSeedAdmin() throws Exception {
        mockMvc.perform(get("/api/assets")).andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"ChangeMe123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.canManageUsers").value(true))
                .andExpect(jsonPath("$.permissions", hasItem("ASSET_DELETE")));
    }

    @Test
    void superAdminCreatesNormalUserWithAllAssetPermissions() throws Exception {
        mockMvc.perform(post("/api/users").with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"operator","password":"Secure123!"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.permissions", hasItem("ASSET_VIEW")))
                .andExpect(jsonPath("$.permissions", hasItem("ASSET_DELETE")));

        long userId = userRepository.findByUsernameIgnoreCase("operator").orElseThrow().getId();
        MockHttpSession beforeReset = loginSession("operator", "Secure123!");
        mockMvc.perform(get("/api/assets").session(beforeReset)).andExpect(status().isOk());
        mockMvc.perform(put("/api/users/{id}", userId).with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"NewSecure456!\"}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches("NewSecure456!",
                userRepository.findById(userId).orElseThrow().getPasswordHash()));
        mockMvc.perform(delete("/api/assets/{id}", Long.MAX_VALUE).session(beforeReset).with(csrf()))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertTrue(beforeReset.isInvalid());

        var loginResult = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operator\",\"password\":\"NewSecure456!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManageUsers").value(false))
                .andExpect(jsonPath("$.permissions", hasItem("ASSET_DELETE")))
                .andReturn();
        var normalSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        mockMvc.perform(get("/api/users").session(normalSession)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/assets").session(normalSession)).andExpect(status().isOk());

        long adminId = userRepository.findAll().stream().filter(account -> account.getRole() == UserRole.SUPER_ADMIN)
                .findFirst().orElseThrow().getId();
        mockMvc.perform(put("/api/users/{id}", adminId).with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"CannotChange123!\"}"))
                .andExpect(status().isBadRequest());

        MockHttpSession secondSession = loginSession("operator", "NewSecure456!");
        mockMvc.perform(delete("/api/users/{id}", userId).with(user("admin").roles("SUPER_ADMIN")).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/assets/{id}", Long.MAX_VALUE).session(normalSession).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/assets").session(secondSession)).andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertTrue(normalSession.isInvalid());
        org.junit.jupiter.api.Assertions.assertTrue(secondSession.isInvalid());
        mockMvc.perform(delete("/api/users/{id}", adminId).with(user("admin").roles("SUPER_ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void superAdminChangesOwnPasswordAfterCurrentPasswordVerification() throws Exception {
        var loginResult = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"ChangeMe123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var adminSession = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(put("/api/auth/password").session(adminSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"WrongPassword!\",\"newPassword\":\"NewAdmin456!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前密码不正确"));

        mockMvc.perform(put("/api/auth/password").session(adminSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"ChangeMe123!\",\"newPassword\":\"NewAdmin456!\"}"))
                .andExpect(status().isNoContent());

        var admin = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches("NewAdmin456!", admin.getPasswordHash()));
        org.junit.jupiter.api.Assertions.assertFalse(passwordEncoder.matches("ChangeMe123!", admin.getPasswordHash()));
        mockMvc.perform(get("/api/users").session(adminSession)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users").session(loginSession("admin", "NewAdmin456!")))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ascii", "unicode"})
    @Transactional
    void rejectsPasswordsOverBcryptByteLimit(String variant) throws Exception {
        String password = variant.equals("ascii") ? "a".repeat(73) : "密".repeat(25);
        mockMvc.perform(post("/api/users").with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"long-password\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest());
        var account = new com.acme.assetmanagement.user.UserAccount();
        account.setUsername("password-boundary");
        account.setDisplayName("password-boundary");
        account.setPasswordHash(passwordEncoder.encode("ValidPass123!"));
        account.setRole(UserRole.USER);
        account.setEnabled(true);
        userRepository.saveAndFlush(account);
        mockMvc.perform(put("/api/users/{id}", account.getId()).with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/auth/password").session(loginSession("admin", "ChangeMe123!")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"ChangeMe123!\",\"newPassword\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpSession loginSession(String username, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    @Test
    void normalUserCannotManageUsersButCanOperateAssets() throws Exception {
        var operator = new com.acme.assetmanagement.user.UserAccount();
        operator.setUsername("legacy-operator"); operator.setDisplayName("旧操作员"); operator.setPasswordHash("test-hash");
        userRepository.saveAndFlush(operator);
        var normalUser = user("operator").authorities(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ASSET_VIEW"),
                new SimpleGrantedAuthority("ASSET_DELETE"));
        mockMvc.perform(get("/api/users").with(normalUser)).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/auth/password").with(normalUser).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Secure123!\",\"newPassword\":\"AnotherSecure456!\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/assets/1")
                        .with(user(com.acme.assetmanagement.security.AccountPrincipal.from(operator))).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void avatarUploadAccessAndDeleteRulesAreEnforced() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        MockMultipartFile avatar = new MockMultipartFile("file", "avatar.png", MediaType.IMAGE_PNG_VALUE, png);

        var adminLogin = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"ChangeMe123!\"}"))
                .andExpect(status().isOk()).andReturn();
        var adminSession = (MockHttpSession) adminLogin.getRequest().getSession(false);
        long adminId = userRepository.findByUsernameIgnoreCase("admin").orElseThrow().getId();

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/auth/avatar").file(avatar)
                        .session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(true));
        mockMvc.perform(get("/api/users/{id}/avatar", adminId).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));
        mockMvc.perform(get("/api/auth/me").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(true));

        mockMvc.perform(post("/api/users").session(adminSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"avataroperator\",\"password\":\"Secure123!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasAvatar").value(false));
        long userId = userRepository.findByUsernameIgnoreCase("avataroperator").orElseThrow().getId();
        var normalLogin = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"avataroperator\",\"password\":\"Secure123!\"}"))
                .andExpect(status().isOk()).andReturn();
        var normalSession = (MockHttpSession) normalLogin.getRequest().getSession(false);

        mockMvc.perform(get("/api/users/{id}/avatar", adminId).session(normalSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/auth/avatar").file(avatar)
                        .session(normalSession).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/users/{id}/avatar", userId).session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(false));
        mockMvc.perform(delete("/api/users/{id}/avatar", adminId).session(adminSession).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/auth/avatar").session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(false));
    }
}
