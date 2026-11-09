package com.acme.assetmanagement.integration.deployment;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ad_deployment_credentials")
public class DeploymentCredential {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 80) String name;
    @Column(nullable = false, length = 64) String secretHash;
    @Column(nullable = false) Instant createdAt;
    @Column(nullable = false) Instant expiresAt;
    Instant revokedAt;
    boolean active() { return revokedAt == null && expiresAt.isAfter(Instant.now()); }
}
