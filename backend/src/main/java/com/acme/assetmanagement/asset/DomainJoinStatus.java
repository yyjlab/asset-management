package com.acme.assetmanagement.asset;

public enum DomainJoinStatus {
    JOINED("已入域", "已由运维确认加入公司域。", 10),
    NOT_JOINED("未入域", "尚未加入公司域，需要先完成加域。", 20),
    UNKNOWN("待确认", "尚未核实入域情况，请检查设备后再选择。", 30);

    private final String label;
    private final String description;
    private final int sortOrder;

    DomainJoinStatus(String label, String description, int sortOrder) {
        this.label = label;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    public String label() { return label; }
    public String description() { return description; }
    public int sortOrder() { return sortOrder; }
}
