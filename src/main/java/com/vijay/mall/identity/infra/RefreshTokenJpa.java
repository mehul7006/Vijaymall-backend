package com.vijay.mall.identity.infra;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code refresh_token} rows: lookup by hash and revoking one token, a device or all of a user's tokens.
 */
interface RefreshTokenJpa extends JpaRepository<RefreshTokenEntity, UUID> {
  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update RefreshTokenEntity r set r.revokedAt = :now where r.id = :id and r.revokedAt is null")
  int revoke(@Param("id") UUID id, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update RefreshTokenEntity r set r.revokedAt = :now where r.userId = :user and r.deviceId = :device and r.revokedAt is null")
  int revokeDevice(@Param("user") UUID user, @Param("device") String device, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update RefreshTokenEntity r set r.revokedAt = :now where r.userId = :user and r.deviceId is null and r.revokedAt is null")
  int revokeNoDevice(@Param("user") UUID user, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update RefreshTokenEntity r set r.revokedAt = :now where r.userId = :user and r.revokedAt is null")
  int revokeAllOf(@Param("user") UUID user, @Param("now") Instant now);
}
