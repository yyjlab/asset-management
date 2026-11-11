package com.acme.assetmanagement.integration.deployment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface DeploymentIntakeRepository extends JpaRepository<DeploymentIntake, String> {
    interface ReportedPerson { String getName(); String getDepartment(); }
    @Query("select i.reportedUserName as name, i.reportedDepartment as department from DeploymentIntake i where i.state = 'pending_review' and i.reportedUserName is not null")
    java.util.List<ReportedPerson> pendingReportedPeople();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from DeploymentIntake i where i.externalId = :id")
    Optional<DeploymentIntake> locked(@Param("id") String id);
    Page<DeploymentIntake> findByStateOrderByReceivedAtDescExternalIdDesc(String state, Pageable pageable);
    Optional<DeploymentIntake> findFirstByOrderByReceivedAtDescExternalIdDesc();
}
