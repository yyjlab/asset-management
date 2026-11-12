package com.acme.assetmanagement.asset;

import jakarta.persistence.*;

@Entity
@Table(name = "accessory_stock", uniqueConstraints = @UniqueConstraint(columnNames = "identity_key"))
public class AccessoryStock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, length = 64, name = "identity_key") String identityKey;
    @Column(nullable = false, length = 120) String type;
    @Column(nullable = false, length = 160) String brand;
    @Column(nullable = false,name="company_id") Long companyId;
    @Column(nullable = false,name="location_id") Long locationId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name="company_id",insertable=false,updatable=false)
    private com.acme.assetmanagement.lookup.LookupValue companyReference;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name="location_id",insertable=false,updatable=false)
    private com.acme.assetmanagement.lookup.LookupValue locationReference;
    @Column(nullable = false) int total;
    @Column(nullable = false) int scrapped;
    protected AccessoryStock() {}
}
