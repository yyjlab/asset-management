package com.acme.assetmanagement.setting;

import com.acme.assetmanagement.asset.DomainJoinStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainStatusPreferenceRepository extends JpaRepository<DomainStatusPreference, DomainJoinStatus> {}
