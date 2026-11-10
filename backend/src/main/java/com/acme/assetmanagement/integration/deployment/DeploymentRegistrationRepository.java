package com.acme.assetmanagement.integration.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentRegistrationRepository extends JpaRepository<DeploymentRegistration, String> {
    org.springframework.data.domain.Page<DeploymentRegistration> findAllByOrderByCreatedAtDescExternalIdDesc(
            org.springframework.data.domain.Pageable pageable);
    java.util.Optional<DeploymentRegistration> findFirstByOrderByCreatedAtDescExternalIdDesc();
}
