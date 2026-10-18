package com.acme.assetmanagement.consumable;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

interface ConsumableItemRepository extends JpaRepository<ConsumableItem, Long>, JpaSpecificationExecutor<ConsumableItem> {
    Optional<ConsumableItem> findByRequestKey(String requestKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConsumableItem c where c.id = :id")
    Optional<ConsumableItem> locked(@Param("id") Long id);
}
