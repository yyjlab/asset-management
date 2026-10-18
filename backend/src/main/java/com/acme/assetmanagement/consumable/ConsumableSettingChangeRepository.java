package com.acme.assetmanagement.consumable;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface ConsumableSettingChangeRepository extends JpaRepository<ConsumableSettingChange, Long> {
    Optional<ConsumableSettingChange> findByRequestKey(String requestKey);
}
