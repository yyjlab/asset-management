package com.acme.assetmanagement.asset;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "registration_roster")
public class RegistrationRoster implements org.springframework.data.domain.Persistable<String> {
    @Id @Column(length = 64) String id;
    @Column(nullable = false, length = 120) String name;
    @Column(length = 120) String department;
    @Column(length = 20) String seat;
    @Column(nullable = false) Instant createdAt;
    @Transient private boolean newRecord=true;
    @Override public String getId(){return id;}
    @Override public boolean isNew(){return newRecord;}
    @PostLoad @PostPersist void markPersisted(){newRecord=false;}
}
