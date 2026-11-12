package com.acme.assetmanagement.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    java.util.List<AuditLog> findFirst100ByTargetTypeAndTargetIdOrderByOccurredAtDesc(String type,Long id);
}
