package com.acme.assetmanagement.operations;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Optional;

interface OperationRepository extends JpaRepository<OperationTask, Long>, JpaSpecificationExecutor<OperationTask> {
    Optional<OperationTask> findByRequestKey(String requestKey);
    boolean existsByReservedAssetId(Long assetId);
    long countByOwnerIdAndCreatedAtAfter(Long ownerId, LocalDateTime after);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OperationTask t where t.id = :id")
    Optional<OperationTask> locked(@Param("id") Long id);
}
