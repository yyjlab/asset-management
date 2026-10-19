package com.acme.assetmanagement.security;

import com.acme.assetmanagement.audit.AuditAction;
import com.acme.assetmanagement.audit.AuditLogService;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/dingtalk")
public class DingTalkController {
    private static final String PENDING = DingTalkController.class.getName() + ".pending";
    private final SecureRandom random = new SecureRandom();
    private final DingTalkProperties properties;
    private final DingTalkClient client;
    private final DingTalkAccountService accounts;
    private final UserRepository users;
    private final AuditLogService audit;

    public DingTalkController(DingTalkProperties properties, DingTalkClient client,
                             DingTalkAccountService accounts, UserRepository users, AuditLogService audit) {
        this.properties = properties;
        this.client = client;
        this.accounts = accounts;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/status")
    public Map<String, Boolean> status(@AuthenticationPrincipal AccountPrincipal principal) {
        boolean bound = principal != null && users.findById(principal.id())
                .map(u -> u.getDingtalkIdentity() != null).orElse(false);
        return Map.of("enabled", properties.enabled(), "bound", bound);
    }

    @GetMapping("/login")
    public void login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.sendRedirect(begin(request, null, false).authorizationUrl());
    }

    @PostMapping("/qr")
    public QrAuthorization qr(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        return begin(request, null, true);
    }

    @PostMapping("/bind")
    public Map<String, String> bind(@AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest request) {
        if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先用账号密码登录");
        return Map.of("authorizationUrl", begin(request, principal, false).authorizationUrl());
    }

    private QrAuthorization begin(HttpServletRequest request, AccountPrincipal principal, boolean embedded) {
        if (!properties.enabled()) throw new ApiException(HttpStatus.NOT_FOUND, "钉钉登录未启用");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var pending = new Pending(state, Instant.now().getEpochSecond(),
                principal == null ? null : principal.id(), principal == null ? null : principal.password());
        var session = request.getSession(true);
        synchronized (session) { session.setAttribute(PENDING, pending); }
        var url = UriComponentsBuilder.fromUriString("https://login.dingtalk.com/oauth2/auth")
                .queryParam("client_id", properties.clientId()).queryParam("response_type", "code")
                .queryParam("scope", "openid").queryParam("prompt", "consent")
                .queryParam("redirect_uri", properties.redirectUri()).queryParam("state", state);
        if (embedded) url.queryParam("iframe", "true");
        return new QrAuthorization(url.build().encode().toUriString(), properties.redirectUri(), state,
                (pending.createdAt() + properties.stateTtlSeconds()) * 1000);
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String state,
                         @RequestParam(required = false) String authCode,
                         @RequestParam(required = false) String code,
                         @RequestParam(required = false) String error,
                         @AuthenticationPrincipal AccountPrincipal current,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        try {
            if (!properties.enabled()) throw new ApiException(HttpStatus.NOT_FOUND, "钉钉登录未启用");
            Pending pending = consume(request, state);
            if (pending.userId() != null && (current == null || !pending.userId().equals(current.id()))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "绑定会话已失效，请重新登录");
            }
            String authorizationCode = authCode != null ? authCode : code;
            if (error != null || authorizationCode == null || authorizationCode.isBlank() || authorizationCode.length() > 2048) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "钉钉授权未完成，请重试");
            }
            AccountPrincipal principal = accounts.resolve(client.unionId(authorizationCode),
                    pending.userId(), pending.passwordHash());
            request.changeSessionId();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
            request.getSession().setAttribute(AuthController.DINGTALK_LOGIN, true);
            audit.authenticationSuccess(principal, AuditAction.LOGIN_SUCCESS,
                    pending.userId() == null ? "钉钉登录系统" : "账号绑定钉钉身份");
            response.sendRedirect(pending.userId() == null ? "/" : "/?dingtalk=bound");
        } catch (ApiException exception) {
            audit.authenticationFailure("钉钉登录", "钉钉登录或绑定失败");
            response.sendRedirect("/?dingtalkError=" + java.net.URLEncoder.encode(exception.getMessage(), StandardCharsets.UTF_8));
        } catch (DataIntegrityViolationException exception) {
            response.sendRedirect("/?dingtalkError=" + java.net.URLEncoder.encode("钉钉身份已被绑定，请重新登录后检查", StandardCharsets.UTF_8));
        }
    }

    private Pending consume(HttpServletRequest request, String state) {
        var session = request.getSession(false);
        Pending pending = null;
        if (session != null) {
            synchronized (session) {
                Object value = session.getAttribute(PENDING);
                session.removeAttribute(PENDING);
                if (value instanceof Pending saved) pending = saved;
            }
        }
        if (pending == null || state == null || state.length() > 128
                || !MessageDigest.isEqual(pending.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))
                || Instant.now().getEpochSecond() - pending.createdAt() > properties.stateTtlSeconds()
                || pending.createdAt() > Instant.now().getEpochSecond()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "授权会话已失效，请重新点击钉钉登录");
        }
        return pending;
    }

    record Pending(String state, long createdAt, Long userId, String passwordHash) implements Serializable {}
    public record QrAuthorization(String authorizationUrl, String redirectUri, String state, long expiresAt) {}
}
