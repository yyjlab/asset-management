package com.acme.assetmanagement.integration.deployment;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ad_deployment_intakes", indexes = @Index(name = "idx_ad_intake_state_received", columnList = "state,receivedAt"))
public class DeploymentIntake {
    @Id @Column(length = 128) String externalId;
    @Version Long persistenceVersion;
    @Column(nullable = false, length = 64) String fingerprint;
    @Column(nullable = false, length = 36) String credentialId;
    @Column(nullable = false) Instant receivedAt;
    @Column(nullable = false, length = 20) String state = "pending_review";
    @Column(nullable = false) int revision;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") String requestJson;
    @Lob @Column(columnDefinition = "LONGTEXT") String reviewJson;
    @Column(length = 64) String reviewFingerprint;
    Long assetId;
    @Column(length = 12) String assetTag;
    Long assetVersion;
    Instant reviewedAt;
    @Column(length = 120) String reviewedBy;
    @Column(length = 500) String reviewNote;
    @Column(length = 32) String source;
    @Column(length = 120) String reportedUserName;
    @Column(length = 120) String reportedDepartment;
}
