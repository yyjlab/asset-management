package com.acme.assetmanagement.lookup;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface AccessoryOptionRepository extends JpaRepository<AccessoryOption, Long> {
    List<AccessoryOption> findAllByOrderByNameAscModelAscIdAsc();
    List<AccessoryOption> findByDisabledFalseOrderByNameAscModelAscIdAsc();
    Optional<AccessoryOption> findByNameIgnoreCaseAndModelIgnoreCaseAndSpecificationIgnoreCase(String name, String model, String specification);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select option from AccessoryOption option where option.id = :id")
    Optional<AccessoryOption> locked(Long id);
}
