package com.acme.assetmanagement.management;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity @Table(name = "equipment_kits")
class EquipmentKit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Version long version;
    @Column(nullable = false, unique = true, length = 36) String requestKey;
    @Column(nullable = false, length = 120) String name;
    @Column(nullable = false, length = 1000) String notes;
    @Column(nullable = false) boolean active = true;
    @Column(nullable = false) LocalDateTime updatedAt = LocalDateTime.now();
    @ElementCollection @CollectionTable(name = "equipment_kit_items", joinColumns = @JoinColumn(name = "kit_id"))
    @OrderColumn(name = "item_order") List<Item> items = new ArrayList<>();
    @Embeddable public static class Item {
        @Column(nullable = false, length = 120) String name;
        int quantity;
        protected Item() {}
        Item(String name, int quantity) { this.name = name; this.quantity = quantity; }
    }
}
