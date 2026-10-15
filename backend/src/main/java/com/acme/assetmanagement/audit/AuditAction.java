package com.acme.assetmanagement.audit;

public enum AuditAction {
    LOGIN_SUCCESS("认证", "登录成功"),
    LOGIN_FAILED("认证", "登录失败"),
    LOGOUT("认证", "退出登录"),
    PASSWORD_CHANGE("用户", "修改密码"),
    ASSET_CREATE("资产", "新增资产"),
    ASSET_CLONE("资产", "克隆资产"),
    ASSET_UPDATE("资产", "编辑资产"),
    ASSET_RETURN("资产", "归还资产"),
    ASSET_DELETE("资产", "删除资产"),
    LOOKUP_CREATE("选项", "新建选项"),
    LOOKUP_DELETE("选项", "删除选项"),
    USER_CREATE("用户", "创建用户"),
    USER_PASSWORD_RESET("用户", "重置密码"),
    USER_ACCESS_UPDATE("用户", "修改权限"),
    AVATAR_UPDATE("用户", "更新头像"),
    AVATAR_DELETE("用户", "删除头像"),
    USER_DELETE("用户", "删除用户"),
    SETTING_UPDATE("设置", "修改设置"),
    CSV_TEMPLATE_EXPORT("导入导出", "导出资产数据"),
    CSV_IMPORT("导入导出", "导入CSV");

    private final String module;
    private final String label;

    AuditAction(String module, String label) {
        this.module = module;
        this.label = label;
    }

    public String getModule() { return module; }
    public String getLabel() { return label; }
}
