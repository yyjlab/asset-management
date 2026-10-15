package com.acme.assetmanagement.user;

import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import com.acme.assetmanagement.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UserController {
    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public UserController(UserRepository repository, PasswordEncoder passwordEncoder, AuditLogService auditLogService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public List<UserResponse> list() {
        return repository.findAllByOrderByRoleAscCreatedAtAsc().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        String username = request.username().trim();
        if (repository.existsByUsernameIgnoreCase(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "账号“" + username + "”已存在");
        }
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setDisplayName(username);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setRole(UserRole.USER);
        account.setPermissions(Set.of(Permission.values()));
        account.setEnabled(true);
        UserAccount saved = repository.save(account);
        auditLogService.success(AuditAction.USER_CREATE, "USER", saved.getId(), saved.getUsername(),
                "创建普通用户“" + saved.getUsername() + "”", java.util.Map.of("账号", saved.getUsername(), "角色", "普通用户"));
        return UserResponse.from(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        UserAccount account = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (account.getRole() == UserRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "超级管理员账号受保护，不能在此修改");
        }
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        UserAccount saved = repository.save(account);
        auditLogService.success(AuditAction.USER_PASSWORD_RESET, "USER", saved.getId(), saved.getUsername(),
                "重置普通用户“" + saved.getUsername() + "”的登录密码", java.util.Map.of());
        return UserResponse.from(saved);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id) {
        UserAccount account = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (account.getRole() == UserRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "超级管理员账号受保护，不能删除");
        }
        repository.delete(account);
        auditLogService.success(AuditAction.USER_DELETE, "USER", id, account.getUsername(),
                "删除普通用户“" + account.getUsername() + "”", java.util.Map.of("账号", account.getUsername()));
    }

    @PutMapping("/{id}/access")
    @Transactional
    public UserResponse access(@PathVariable Long id, @Valid @RequestBody AccessRequest request) {
        UserAccount account = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (account.getRole() == UserRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "超级管理员权限受保护");
        }
        Set<Permission> permissions = request.permissions();
        if (!permissions.isEmpty() && !permissions.contains(Permission.ASSET_VIEW)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "资产操作必须同时授予查看资产权限");
        }
        if (permissions.contains(Permission.ASSET_IMPORT) && !permissions.contains(Permission.ASSET_CREATE)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "导入资产必须同时授予新增资产权限");
        }
        Set<String> departments = request.departments().stream().map(String::trim)
                .filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toSet());
        if (request.dataScope() == DataScope.DEPARTMENT && departments.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请选择允许访问的部门");
        }
        account.setPermissions(permissions);
        account.setDataScope(request.dataScope());
        account.setDepartments(request.dataScope() == DataScope.DEPARTMENT ? departments : Set.of());
        account.setAccessConfigured(true);
        account.setEnabled(request.enabled());
        repository.saveAndFlush(account);
        auditLogService.success(AuditAction.USER_ACCESS_UPDATE, "USER", id, account.getUsername(),
                "修改用户权限和数据范围", java.util.Map.of("权限", permissions, "数据范围", request.dataScope(),
                        "部门", departments, "启用", request.enabled()));
        return UserResponse.from(account);
    }

    public record AccessRequest(
            @jakarta.validation.constraints.NotNull Set<@jakarta.validation.constraints.NotNull Permission> permissions,
            @jakarta.validation.constraints.NotNull DataScope dataScope,
            @jakarta.validation.constraints.NotNull @Size(max = 50) Set<@NotBlank @Size(max = 120) String> departments,
            boolean enabled) {}

    public record CreateUserRequest(
            @NotBlank(message = "请输入账号") @Size(max = 60, message = "账号不能超过60个字符") String username,
            @NotBlank(message = "请输入密码") @Size(min = 8, max = 80, message = "密码长度需要在8到80位之间") String password
    ) {
        @jakarta.validation.constraints.AssertTrue(message = "密码的 UTF-8 编码不能超过72字节")
        public boolean isPasswordWithinByteLimit() {
            return password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
        }
    }

    public record UpdateUserRequest(
            @NotBlank(message = "请输入新密码") @Size(min = 8, max = 80, message = "密码长度需要在8到80位之间") String password
    ) {
        @jakarta.validation.constraints.AssertTrue(message = "密码的 UTF-8 编码不能超过72字节")
        public boolean isPasswordWithinByteLimit() {
            return password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
        }
    }

    public record UserResponse(Long id, String username, UserRole role,
                               Set<Permission> permissions, boolean enabled, java.time.LocalDateTime createdAt,
                               java.time.LocalDateTime updatedAt, boolean hasAvatar, String displayName,
                               boolean dingtalkBound, DataScope dataScope, Set<String> departments) {
        static UserResponse from(UserAccount account) {
            Set<Permission> permissions = account.effectivePermissions();
            return new UserResponse(account.getId(), account.getUsername(), account.getRole(),
                    permissions, account.isEnabled(), account.getCreatedAt(), account.getUpdatedAt(), account.hasAvatar(),
                    account.getDisplayName(), account.getDingtalkIdentity() != null, account.getDataScope(), Set.copyOf(account.getDepartments()));
        }
    }
}
