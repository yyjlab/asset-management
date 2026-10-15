package com.acme.assetmanagement.security;

import com.acme.assetmanagement.user.Permission;
import com.acme.assetmanagement.user.UserAccount;
import com.acme.assetmanagement.user.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public record AccountPrincipal(
        Long id,
        String username,
        String password,
        UserRole role,
        Set<Permission> permissions,
        boolean enabled
) implements UserDetails, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static AccountPrincipal from(UserAccount account) {
        Set<Permission> effectivePermissions = account.effectivePermissions();
        return new AccountPrincipal(account.getId(), account.getUsername(), account.getPasswordHash(),
                account.getRole(), effectivePermissions, account.isEnabled());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission.name())));
        return authorities;
    }

    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return password; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }
}
