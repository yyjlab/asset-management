package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;
import java.util.Objects;

@Component
@Transactional(readOnly = true)
public class AssetAccess {
    private final UserRepository users;
    public AssetAccess(UserRepository users) { this.users = users; }
    public record Scope(DataScope type, Long userId, Set<String> departments) {
        public boolean allows(Asset asset) {
            return type == DataScope.ALL || type == DataScope.SELF && Objects.equals(userId, asset.getAssignedUserId())
                    || type == DataScope.DEPARTMENT && asset.getOwnershipDepartment() != null
                    && departments.contains(asset.getOwnershipDepartment());
        }
    }
    public Scope scope() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        var account = users.findById(principal.id()).filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "账号不可用"));
        return new Scope(account.getDataScope(), account.getId(), Set.copyOf(account.getDepartments()));
    }
    public void require(Asset asset) { require(scope(), asset); }
    public void require(Scope scope, Asset asset) {
        if (!scope.allows(asset)) throw new ApiException(HttpStatus.NOT_FOUND, "资产不存在或无权访问");
    }
    public void permission(Permission permission) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(permission.name()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "没有此操作权限");
        }
    }
}
