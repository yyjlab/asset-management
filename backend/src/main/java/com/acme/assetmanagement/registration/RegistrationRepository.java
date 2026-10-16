package com.acme.assetmanagement.registration;

import org.springframework.data.jpa.repository.*;
import java.time.LocalDateTime;
import java.util.*;

public interface RegistrationRepository extends JpaRepository<AssetRegistration, Long>, JpaSpecificationExecutor<AssetRegistration> {
    Optional<AssetRegistration> findByRequestKey(String requestKey);
    long countByOwnerIdAndCreatedAtAfter(Long ownerId, LocalDateTime createdAt);
    boolean existsByOwnerIdAndSerialKeyAndStateInAndIdNot(Long ownerId, String serialKey, Collection<String> states, Long id);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AssetRegistration r where r.id = :id")
    Optional<AssetRegistration> findForReview(@org.springframework.data.repository.query.Param("id") Long id);
}
