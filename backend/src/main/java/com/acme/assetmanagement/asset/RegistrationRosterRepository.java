package com.acme.assetmanagement.asset;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationRosterRepository extends JpaRepository<RegistrationRoster, String> {
    @org.springframework.data.jpa.repository.Query("select r.department from RegistrationRoster r where r.id = :id")
    java.util.Optional<String> departmentByNameHash(@org.springframework.data.repository.query.Param("id") String id);
}
