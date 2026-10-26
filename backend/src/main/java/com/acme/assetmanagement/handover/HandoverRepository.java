package com.acme.assetmanagement.handover;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

public interface HandoverRepository extends JpaRepository<HandoverTask, Long>, JpaSpecificationExecutor<HandoverTask> {
    long countByStateIn(java.util.Collection<String> states);
    long countByStateInAndPlannedDateBefore(java.util.Collection<String> states, java.time.LocalDate date);
    java.util.List<HandoverTask> findTop10ByStateInOrderByPlannedDateAscIdAsc(java.util.Collection<String> states);
    Optional<HandoverTask> findByRequestKey(String requestKey);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from HandoverTask t where t.id = :id")
    Optional<HandoverTask> locked(@org.springframework.data.repository.query.Param("id") Long id);
}
