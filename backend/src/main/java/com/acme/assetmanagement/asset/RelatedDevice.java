package com.acme.assetmanagement.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class RelatedDevice {
    @Column(name = "device_name", nullable = false, length = 120)
    private String name;

    @Column(name = "device_model", length = 160)
    private String model;

    @Column(name = "device_serial_number", length = 160)
    private String serialNumber;

    @Column(name = "device_order_number", length = 160)
    private String orderNumber;

    @Column(name = "device_specification", length = 300)
    private String specification;

    @Column(name = "device_quantity", nullable = false)
    private int quantity = 1;
    @Column(name = "stock_id")
    private Long stockId;
    @jakarta.persistence.ManyToOne(fetch=jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name="stock_id",insertable=false,updatable=false)
    private AccessoryStock stockReference;
    public Long getStockId(){return stockId;}
    public void setStockId(Long value){stockId=value;}

    public RelatedDevice() {}

    public RelatedDevice(String name, String model, String serialNumber, String orderNumber, String specification, int quantity) {
        this.name = name;
        this.model = model;
        this.serialNumber = serialNumber;
        this.orderNumber = orderNumber;
        this.specification = specification;
        this.quantity = quantity;
    }

    public String getName() { return name; }
    public String getModel() { return model; }
    public String getSerialNumber() { return serialNumber; }
    public String getOrderNumber() { return orderNumber; }
    public String getSpecification() { return specification; }
    public int getQuantity() { return quantity; }
}
