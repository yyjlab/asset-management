package com.acme.assetmanagement.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsernameIgnoreCase(String username);
    Optional<UserAccount> findByDingtalkIdentity(String identity);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> findForDingtalkBinding(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> findForWorkflowUpdate(@org.springframework.data.repository.query.Param("id") Long id);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByRole(UserRole role);
    boolean existsByIdAndEnabledTrueAndPasswordHashAndRole(Long id, String passwordHash, UserRole role);
    List<UserAccount> findAllByOrderByRoleAscCreatedAtAsc();
}
