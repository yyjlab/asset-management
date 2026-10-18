package com.acme.assetmanagement.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

interface InventoryRepository extends JpaRepository<InventoryBatch, Long>, JpaSpecificationExecutor<InventoryBatch> {
    Optional<InventoryBatch> findByRequestKey(String requestKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select b from InventoryBatch b where b.id = :id")
    Optional<InventoryBatch> locked(@Param("id") Long id);
}
