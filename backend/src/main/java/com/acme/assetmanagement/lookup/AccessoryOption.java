package com.acme.assetmanagement.lookup;

import jakarta.persistence.*;

/** Reusable catalogue templates; assets retain their own independent accessory snapshots. */
@Entity
@Table(name = "accessory_options", uniqueConstraints = @UniqueConstraint(name = "uk_accessory_option", columnNames = {"name", "model", "specification"}))
public class AccessoryOption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, length = 120) String name;
    @Column(nullable = false, length = 160) String model;
    @Column(nullable = false, length = 300) String specification;
    @Column(nullable = false) boolean disabled;

    protected AccessoryOption() {}
    AccessoryOption(String name, String model, String specification) {
        update(name, model, specification);
    }
    void update(String name, String model, String specification) {
        this.name = name; this.model = model; this.specification = specification;
    }
}
