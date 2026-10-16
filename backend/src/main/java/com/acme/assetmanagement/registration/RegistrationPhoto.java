package com.acme.assetmanagement.registration;

import jakarta.persistence.*;

@Entity
@Table(name = "asset_registration_photos", indexes = @Index(name = "idx_registration_photos", columnList = "registrationId,revision"))
public class RegistrationPhoto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false) Long registrationId;
    int revision;
    @Column(nullable = false, length = 30) String mediaType;
    @Lob @Column(nullable = false, columnDefinition = "LONGBLOB") byte[] data;
    protected RegistrationPhoto() {}
}
