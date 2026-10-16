package com.acme.assetmanagement.handover;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

public interface HandoverRepository extends JpaRepository<HandoverTask, Long>, JpaSpecificationExecutor<HandoverTask> {
    long countByStateIn(java.util.Collection<String> states);
    long countByStateInAndPlannedDateBefore(java.util.Collection<String> states, java.time.LocalDate date);
    java.util.List<HandoverTask> findTop10ByStateInOrderByPlannedDateAscIdAsc(java.util.Collection<String> states);
    Optional<HandoverTask> findByRequestKey(String requestKey);
}
