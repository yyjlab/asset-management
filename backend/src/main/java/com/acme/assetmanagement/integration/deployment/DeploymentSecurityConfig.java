package com.acme.assetmanagement.integration.deployment;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Configuration
public class DeploymentSecurityConfig {
    public static final String PATH = "/api/integrations/ad-deployment/v1/registrations";
    public static final String INTAKES = "/api/integrations/ad-deployment/v1/intakes";
    public static final String CONNECTION = "/api/integrations/ad-deployment/v1/connection";
    static final String BODY = "adDeploymentRegistrationBody";
    static final int MAX_BYTES = 32768;
    @Bean @Order(1)
    SecurityFilterChain deploymentSecurity(HttpSecurity http, DeploymentCredentialService credentials,
                                          DeploymentAccessService properties) throws Exception {
        http.securityMatcher("/api/integrations/ad-deployment/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new org.springframework.security.web.context.NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable()).csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .addFilterBefore(new TokenFilter(credentials, properties), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, PATH, INTAKES).hasAuthority(DeploymentPrincipal.AUTHORITY)
                        .requestMatchers(HttpMethod.GET, INTAKES + "/*").hasAuthority(DeploymentPrincipal.AUTHORITY)
                        .requestMatchers(HttpMethod.GET, CONNECTION).hasAuthority(DeploymentPrincipal.AUTHORITY)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> error(response, 401, "AUTHENTICATION_FAILED", "需要有效的专用服务凭据"))
                        .accessDeniedHandler((request, response, exception) -> error(response, 403, "FORBIDDEN", "专用凭据仅允许登记和接入检查")));
        return http.build();
    }
    static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setCharacterEncoding("UTF-8"); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"fields\":[]}");
    }
    static final class TokenFilter extends OncePerRequestFilter {
        private final DeploymentCredentialService credentials;
        private final DeploymentAccessService properties;
        TokenFilter(DeploymentCredentialService credentials, DeploymentAccessService properties) {
            this.credentials = credentials; this.properties = properties;
        }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            if (!properties.isEnabled()) { error(response, 503, "INTEGRATION_DISABLED", "装机平台登记接口未启用"); return; }
            if (!properties.allowsRequest(request.isSecure())) { error(response, 403, "HTTPS_REQUIRED", "请使用HTTPS，或由管理员在装机平台接入页面确认开启内网HTTP"); return; }
            var headers = java.util.Collections.list(request.getHeaders("Authorization"));
            String value = headers.size() == 1 ? headers.getFirst() : null;
            var principal = value != null && value.startsWith("Bearer ") ? credentials.authenticate(value.substring(7)) : null;
            if (principal == null) { error(response, 401, "AUTHENTICATION_FAILED", "专用凭据无效、已过期或已撤销"); return; }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                    List.of(new SimpleGrantedAuthority(DeploymentPrincipal.AUTHORITY))));
            SecurityContextHolder.setContext(context);
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if ("POST".equals(request.getMethod()) && (PATH.equals(path) || INTAKES.equals(path))) {
                if (request.getContentType() == null || !request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                    error(response, 415, "UNSUPPORTED_MEDIA_TYPE", "仅支持 application/json"); return;
                }
                byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) { error(response, 413, "PAYLOAD_TOO_LARGE", "请求不能超过32KiB"); return; }
                request.setAttribute(BODY, body);
            }
            chain.doFilter(request, response);
        }
    }
}
