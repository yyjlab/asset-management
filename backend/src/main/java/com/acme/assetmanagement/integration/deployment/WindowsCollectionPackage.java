package com.acme.assetmanagement.integration.deployment;

import jakarta.persistence.*;

@Entity
@Table(name = "windows_collection_packages")
public class WindowsCollectionPackage {
    @Id @Column(length = 36) String id;
}
