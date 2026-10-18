package com.acme.assetmanagement.procurement;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
interface ProcurementRepository extends JpaRepository<ProcurementOrder, Long>, JpaSpecificationExecutor<ProcurementOrder> {
    Optional<ProcurementOrder> findByRequestKey(String key);
    long countByOwnerIdAndCreatedAtAfter(Long ownerId, LocalDateTime after);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from ProcurementOrder o where o.id = :id") Optional<ProcurementOrder> locked(@Param("id") Long id);
}
