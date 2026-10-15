package com.acme.assetmanagement.user;

public enum Permission {
    ASSET_VIEW("查看资产"),
    ASSET_CREATE("新增资产"),
    ASSET_EDIT("编辑资产"),
    ASSET_DELETE("删除资产"),
    ASSET_RETURN("归还资产"),
    ASSET_CHECKOUT("分配领用人"),
    ASSET_IMPORT("导入资产"),
    ASSET_EXPORT("导出资产");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
