package com.acme.assetmanagement.security;

import com.acme.assetmanagement.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class AccountSessionFilter extends OncePerRequestFilter {
    private final UserRepository repository;

    public AccountSessionFilter(UserRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            var account = repository.findById(principal.id()).orElse(null);
            if (account == null || !account.isEnabled() || !account.getPasswordHash().equals(principal.password())
                    || account.getRole() != principal.role()) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
            } else {
                // Refresh authorities on every request: revocation must also affect existing sessions.
                var refreshed = AccountPrincipal.from(account);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated(refreshed, null, refreshed.getAuthorities()));
                SecurityContextHolder.setContext(context);
            }
        }
        filterChain.doFilter(request, response);
    }
}
