package com.acme.assetmanagement.management;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
interface SupplierRepository extends JpaRepository<Supplier, Long> {
    Optional<Supplier> findByRequestKey(String key);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from Supplier s where s.id = :id") Optional<Supplier> locked(@Param("id") Long id);
}
