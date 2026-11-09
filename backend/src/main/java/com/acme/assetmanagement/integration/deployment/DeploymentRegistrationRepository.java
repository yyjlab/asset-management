package com.acme.assetmanagement.integration.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentRegistrationRepository extends JpaRepository<DeploymentRegistration, String> {}
