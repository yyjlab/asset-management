package com.acme.assetmanagement.security;

import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import com.acme.assetmanagement.user.Permission;
import com.acme.assetmanagement.user.UserAccount;
import com.acme.assetmanagement.user.UserRepository;
import com.acme.assetmanagement.user.UserRole;
import com.acme.assetmanagement.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, UserRepository userRepository,
                          PasswordEncoder passwordEncoder, AuditLogService auditLogService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username().trim(), body.password()));
            if (request.getSession(false) != null) request.changeSessionId();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            AccountPrincipal principal = (AccountPrincipal) authentication.getPrincipal();
            auditLogService.authenticationSuccess(principal, AuditAction.LOGIN_SUCCESS, "账号登录系统");
            return AuthResponse.from(requireAccount(principal.id()));
        } catch (AuthenticationException exception) {
            auditLogService.authenticationFailure(body.username(), "账号或密码错误");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
    }

    @GetMapping("/me")
    public AuthResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return AuthResponse.from(requireAccount(principal.id()));
    }

    private UserAccount requireAccount(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "当前账号不存在"));
    }

    @PutMapping("/password")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(@AuthenticationPrincipal AccountPrincipal principal,
                               @Valid @RequestBody ChangePasswordRequest body) {
        UserAccount account = userRepository.findById(principal.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "当前账号不存在"));
        if (account.getRole() != UserRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有超级管理员可以执行此操作");
        }
        if (!passwordEncoder.matches(body.currentPassword(), account.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        if (passwordEncoder.matches(body.newPassword(), account.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "新密码不能与当前密码相同");
        }
        account.setPasswordHash(passwordEncoder.encode(body.newPassword()));
        userRepository.save(account);
        auditLogService.success(AuditAction.PASSWORD_CHANGE, "USER", account.getId(), account.getUsername(),
                "超级管理员修改了自己的登录密码", java.util.Map.of());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest request) {
        if (principal != null) {
            auditLogService.authenticationSuccess(principal, AuditAction.LOGOUT, "账号退出系统");
        }
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) request.getSession(false).invalidate();
    }

    public record LoginRequest(@NotBlank(message = "请输入账号") String username,
                               @NotBlank(message = "请输入密码") String password) {}
    public record ChangePasswordRequest(
            @NotBlank(message = "请输入当前密码") String currentPassword,
            @NotBlank(message = "请输入新密码")
            @Size(min = 8, max = 80, message = "新密码长度需要在8到80位之间") String newPassword
    ) {
        @jakarta.validation.constraints.AssertTrue(message = "新密码的 UTF-8 编码不能超过72字节")
        public boolean isPasswordWithinByteLimit() {
            return newPassword == null || newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
        }
    }
    public record CsrfResponse(String headerName, String token) {}
    public record AuthResponse(Long id, String username, UserRole role,
                               Set<Permission> permissions, boolean canManageUsers, boolean hasAvatar,
                               String displayName, com.acme.assetmanagement.user.DataScope dataScope, Set<String> departments) {
        static AuthResponse from(UserAccount account) {
            return new AuthResponse(account.getId(), account.getUsername(), account.getRole(),
                    account.effectivePermissions(), account.getRole() == UserRole.SUPER_ADMIN, account.hasAvatar(),
                    account.getDisplayName(), account.getDataScope(), Set.copyOf(account.getDepartments()));
        }
    }
}
