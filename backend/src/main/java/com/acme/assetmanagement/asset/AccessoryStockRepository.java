package com.acme.assetmanagement.asset;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface AccessoryStockRepository extends JpaRepository<AccessoryStock, Long> {
    boolean existsByIdentityKey(String identityKey);
    boolean existsByCompanyIdOrLocationId(Long companyId,Long locationId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AccessoryStock s where s.id = :id")
    Optional<AccessoryStock> locked(Long id);
}
