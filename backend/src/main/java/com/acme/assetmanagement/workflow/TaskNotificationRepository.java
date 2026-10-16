package com.acme.assetmanagement.workflow;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface TaskNotificationRepository extends JpaRepository<TaskNotification, Long> {
    boolean existsByEventKey(String eventKey);
    long countByState(String state);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from TaskNotification n where n.id = :id")
    Optional<TaskNotification> locked(@Param("id") Long id);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from TaskNotification n where (n.state = 'PENDING' and n.nextAttemptAt <= :now) or (n.state = 'CLAIMED' and n.leasedUntil < :now) order by n.id")
    List<TaskNotification> claimable(@Param("now") Instant now, Pageable pageable);
}
