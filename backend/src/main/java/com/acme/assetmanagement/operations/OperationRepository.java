package com.acme.assetmanagement.operations;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Optional;

interface OperationRepository extends JpaRepository<OperationTask, Long>, JpaSpecificationExecutor<OperationTask> {
    Optional<OperationTask> findByRequestKey(String requestKey);
    Optional<OperationTask> findByOaInstanceId(String instanceId);
    @Query("select t.id from OperationTask t where t.oaInstanceId is not null and t.oaState in ('WAITING', 'CHECK_FAILED') and (t.oaDirty = true or t.oaCheckedAt is null or t.oaCheckedAt < :before) order by t.oaCheckedAt asc, t.id asc")
    java.util.List<Long> oaPending(@Param("before") LocalDateTime before, org.springframework.data.domain.Pageable page);
    boolean existsByReservedAssetId(Long assetId);
    long countByOwnerIdAndCreatedAtAfter(Long ownerId, LocalDateTime after);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OperationTask t where t.id = :id")
    Optional<OperationTask> locked(@Param("id") Long id);
}
