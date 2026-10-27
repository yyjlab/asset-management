package com.acme.assetmanagement.lookup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LookupRepository extends JpaRepository<LookupValue, Long> {
    List<LookupValue> findAllByOrderByTypeAscNameAsc();
    List<LookupValue> findByTypeOrderByNameAsc(LookupType type);
    Optional<LookupValue> findByTypeAndNameIgnoreCase(LookupType type, String name);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select v from LookupValue v where v.id = :id")
    Optional<LookupValue> locked(@org.springframework.data.repository.query.Param("id") Long id);
}

