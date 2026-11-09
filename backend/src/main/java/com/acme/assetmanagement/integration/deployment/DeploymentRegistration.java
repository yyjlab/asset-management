package com.acme.assetmanagement.integration.deployment;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ad_deployment_registrations")
public class DeploymentRegistration {
    @Id @Column(length = 128) String externalId;
    @Column(nullable = false, length = 64) String fingerprint;
    @Column(nullable = false, length = 36) String credentialId;
    @Column(nullable = false, length = 12) String assetTag;
    @Column(nullable = false) Long assetId;
    @Column(nullable = false) Instant createdAt;
    @Lob @Column(nullable = false) String requestJson;
    @Lob @Column(nullable = false) String responseJson;
}
