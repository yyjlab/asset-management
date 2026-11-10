package com.acme.assetmanagement.integration.deployment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface DeploymentIntakeRepository extends JpaRepository<DeploymentIntake, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from DeploymentIntake i where i.externalId = :id")
    Optional<DeploymentIntake> locked(@Param("id") String id);
    Page<DeploymentIntake> findByStateOrderByReceivedAtDescExternalIdDesc(String state, Pageable pageable);
    Optional<DeploymentIntake> findFirstByOrderByReceivedAtDescExternalIdDesc();
}
