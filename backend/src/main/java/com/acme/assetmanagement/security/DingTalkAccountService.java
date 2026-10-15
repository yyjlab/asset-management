package com.acme.assetmanagement.security;

import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.user.UserAccount;
import com.acme.assetmanagement.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class DingTalkAccountService {
    private final UserRepository repository;
    private final DingTalkProperties properties;
    private final DingTalkClient client;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;
    private final com.acme.assetmanagement.audit.AuditLogService audit;

    public DingTalkAccountService(UserRepository repository, DingTalkProperties properties, DingTalkClient client,
                                 org.springframework.security.crypto.password.PasswordEncoder encoder,
                                 com.acme.assetmanagement.audit.AuditLogService audit) {
        this.repository = repository;
        this.properties = properties;
        this.client = client;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional
    public AccountPrincipal resolve(String unionId, Long bindingUserId, String passwordHash) {
        String identity = identity(unionId);
        UserAccount account;
        if (bindingUserId == null) {
            account = repository.findByDingtalkIdentity(identity)
                    .orElseGet(() -> provision(unionId, identity));
        } else {
            account = repository.findForDingtalkBinding(bindingUserId)
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "绑定账号不存在"));
            if (!account.isEnabled() || !account.getPasswordHash().equals(passwordHash)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "账号状态已变更，请重新登录后绑定");
            }
            if (account.getDingtalkIdentity() != null && !identity.equals(account.getDingtalkIdentity())) {
                throw new ApiException(HttpStatus.CONFLICT, "该账号已绑定其他钉钉身份");
            }
            var existing = repository.findByDingtalkIdentity(identity);
            if (existing.isPresent() && !existing.get().getId().equals(bindingUserId)) {
                throw new ApiException(HttpStatus.CONFLICT, "此钉钉身份已绑定其他账号");
            }
            account.setDingtalkIdentity(identity);
            repository.saveAndFlush(account);
        }
        if (!account.isEnabled()) throw new ApiException(HttpStatus.FORBIDDEN, "账号已停用");
        return AccountPrincipal.from(account);
    }

    private UserAccount provision(String unionId, String identity) {
        String name = client.employeeName(unionId);
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new ApiException(HttpStatus.FORBIDDEN, "未取得企业员工姓名，无法自动创建账号");
        }
        String base = me.majiajie.tinypinyin.Pinyin.toPinyin(name, "").toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        if (base.isBlank()) base = "dingtalk" + identity.substring(0, 8);
        if (base.length() > 48) base = base.substring(0, 48);
        String username = base;
        int suffix = 2;
        while (repository.existsByUsernameIgnoreCase(username)) username = base + suffix++;
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setDisplayName(name.trim());
        // Unguessable random password: auto-created users use DingTalk unless an admin resets it.
        account.setPasswordHash(encoder.encode(java.util.UUID.randomUUID().toString()));
        account.setDingtalkIdentity(identity);
        account.setRole(com.acme.assetmanagement.user.UserRole.USER);
        account.setPermissions(java.util.Set.of(com.acme.assetmanagement.user.Permission.ASSET_VIEW));
        account.setDataScope(com.acme.assetmanagement.user.DataScope.SELF);
        account.setAccessConfigured(true);
        account = repository.saveAndFlush(account);
        audit.success(com.acme.assetmanagement.audit.AuditAction.USER_CREATE, "USER", account.getId(), username,
                "钉钉首次登录自动创建普通账号", java.util.Map.of("姓名", name, "账号", username, "数据范围", "仅本人"));
        return account;
    }

    private String identity(String unionId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((properties.clientId() + "\0" + unionId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
