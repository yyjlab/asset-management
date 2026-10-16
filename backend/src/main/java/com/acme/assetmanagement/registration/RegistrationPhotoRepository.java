package com.acme.assetmanagement.registration;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RegistrationPhotoRepository extends JpaRepository<RegistrationPhoto, Long> {
    interface Info { Long getId(); Long getRegistrationId(); String getMediaType(); }
    List<Info> findInfoByRegistrationIdAndRevisionOrderById(Long registrationId, int revision);
    Optional<Info> findInfoById(Long id);
    List<RegistrationPhoto> findByRegistrationIdAndRevisionOrderById(Long registrationId, int revision);
}
