package com.acme.assetmanagement.integration.deployment;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface DeploymentCredentialRepository extends JpaRepository<DeploymentCredential, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DeploymentCredential c where c.id = :id")
    Optional<DeploymentCredential> locked(@Param("id") String id);
    List<DeploymentCredential> findAllByOrderByCreatedAtDesc();
}
