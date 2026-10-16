package com.acme.assetmanagement.workflow;

import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Component
@Transactional(readOnly = true)
public class WorkflowAccess {
    private final UserRepository users;
    public WorkflowAccess(UserRepository users) { this.users = users; }
    public UserAccount current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AccountPrincipal principal))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        return users.findById(principal.id()).filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "账号不可用"));
    }
    public boolean canReview(UserAccount user) {
        return user.getDataScope() == DataScope.ALL && user.effectivePermissions().containsAll(
                Set.of(Permission.ASSET_VIEW, Permission.ASSET_CREATE, Permission.ASSET_CHECKOUT));
    }
    public UserAccount reviewer() {
        var user = current();
        if (!canReview(user)) throw new ApiException(HttpStatus.FORBIDDEN, "登记审核需要全部资产范围、查看资产、新增资产及分配领用人权限");
        return user;
    }
    public UserAccount administrator() {
        var user = current();
        if (user.getRole() != UserRole.SUPER_ADMIN) throw new ApiException(HttpStatus.FORBIDDEN, "仅超级管理员可管理通知");
        return user;
    }
}
