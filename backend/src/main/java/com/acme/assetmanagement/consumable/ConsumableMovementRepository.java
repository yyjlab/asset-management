package com.acme.assetmanagement.consumable;

import org.springframework.data.jpa.repository.*;
import java.util.Optional;

interface ConsumableMovementRepository extends JpaRepository<ConsumableMovement, Long>, JpaSpecificationExecutor<ConsumableMovement> {
    Optional<ConsumableMovement> findByRequestKey(String requestKey);
}
