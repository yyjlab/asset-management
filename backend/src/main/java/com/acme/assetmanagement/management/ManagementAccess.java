package com.acme.assetmanagement.management;

import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.operations.OperationService;
import com.acme.assetmanagement.user.*;
import com.acme.assetmanagement.workflow.WorkflowAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ManagementAccess {
    private final WorkflowAccess access;
    public ManagementAccess(WorkflowAccess access) { this.access = access; }
    public UserAccount current() { return access.current(); }
    public UserAccount manager() {
        var user = current();
        if (!OperationService.canManage(user)) throw new ApiException(HttpStatus.FORBIDDEN, "需要全部资产范围及查看、编辑、分配领用人、归还资产权限");
        return user;
    }
    public static String key(String value) {
        try { String key = java.util.UUID.fromString(value).toString(); if (!key.equalsIgnoreCase(value)) throw new IllegalArgumentException(); return key; }
        catch (IllegalArgumentException | NullPointerException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "请求标识必须是完整UUID"); }
    }
    public static String clean(String value) { return value == null ? "" : value.trim(); }
}
