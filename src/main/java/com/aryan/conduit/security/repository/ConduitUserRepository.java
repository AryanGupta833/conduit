package com.aryan.conduit.security.repository;

import com.aryan.conduit.security.entity.ConduitUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ConduitUserRepository extends JpaRepository<ConduitUser, Long> {
    Optional<ConduitUser> findByEmailIgnoreCase(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ConduitUser u where u.id = :id")
    Optional<ConduitUser> findByIdForUpdate(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ConduitUser u where lower(u.email) = lower(:email)")
    Optional<ConduitUser> findByEmailForUpdate(@Param("email") String email);
    List<ConduitUser> findAllByOrderByEmailAsc();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ConduitUser u where u.role = com.aryan.conduit.security.entity.AccountRole.ADMIN and u.enabled = true order by u.id")
    List<ConduitUser> lockEnabledAdministrators();
    long countByRoleAndEnabledTrue(com.aryan.conduit.security.entity.AccountRole role);
    java.util.Optional<ConduitUser> findFirstByRoleAndEnabledTrueOrderByIdAsc(com.aryan.conduit.security.entity.AccountRole role);
}
