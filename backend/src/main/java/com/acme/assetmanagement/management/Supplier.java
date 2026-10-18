package com.acme.assetmanagement.management;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "asset_suppliers")
class Supplier {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true, length = 36) String requestKey;
    @Column(nullable = false, length = 120) String name;
    @Column(length = 80) String contact;
    @Column(length = 40) String phone;
    @Column(length = 150) String email;
    @Column(length = 1000) String notes;
    @Column(nullable = false) boolean active = true;
    @Column(nullable = false) LocalDateTime updatedAt = LocalDateTime.now();
}
