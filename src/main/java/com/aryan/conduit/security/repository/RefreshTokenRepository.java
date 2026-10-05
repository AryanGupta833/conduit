package com.aryan.conduit.security.repository;

import com.aryan.conduit.security.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
    List<RefreshToken> findByFamilyId(UUID familyId);
    List<RefreshToken> findByUser_IdAndRevokedAtIsNull(Long userId);
    long deleteByFamilyExpiresAtBefore(java.time.LocalDateTime cutoff);
}
