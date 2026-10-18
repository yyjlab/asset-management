package com.acme.assetmanagement.management;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

interface EquipmentKitRepository extends JpaRepository<EquipmentKit, Long> {
    Optional<EquipmentKit> findByRequestKey(String key);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select k from EquipmentKit k where k.id = :id") Optional<EquipmentKit> locked(@Param("id") Long id);
}
