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

    public DingTalkAccountService(UserRepository repository, DingTalkProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public AccountPrincipal resolve(String unionId, Long bindingUserId, String passwordHash) {
        String identity = identity(unionId);
        UserAccount account;
        if (bindingUserId == null) {
            account = repository.findByDingtalkIdentity(identity)
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                            "此钉钉身份尚未绑定账号，请先用账号密码登录，在头像窗口绑定钉钉"));
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

    private String identity(String unionId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((properties.clientId() + "\0" + unionId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
