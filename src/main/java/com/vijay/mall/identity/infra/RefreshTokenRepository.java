package com.vijay.mall.identity.infra;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores refresh tokens by hash so the clear token is never kept, and revokes them. */
@Repository
public class RefreshTokenRepository {
  /**
   * A stored refresh token as the token service needs it.
   * @param id row id
   * @param userId owner
   * @param deviceId device it was issued to, may be null
   * @param expiresAt when it stops being valid
   * @param revoked true once it was used, logged out or revoked
   */
  public record Row(UUID id, UUID userId, String deviceId, Instant expiresAt, boolean revoked) {}

  private final RefreshTokenJpa jpa;

  RefreshTokenRepository(RefreshTokenJpa jpa) { this.jpa = jpa; }

  /** Stores a new refresh token's hash with its expiry and device. */
  @Transactional
  public void insert(UUID userId, String hash, String deviceId, Instant expires, Instant now) {
    RefreshTokenEntity e = new RefreshTokenEntity();
    e.id = UUID.randomUUID();
    e.userId = userId;
    e.tokenHash = hash;
    e.deviceId = deviceId;
    e.expiresAt = expires;
    e.createdAt = now;
    jpa.save(e);
  }

  /** Finds a refresh token by the SHA-256 hash of what the app sent. */
  public Optional<Row> findByHash(String hash) {
    return jpa.findByTokenHash(hash).map(e -> new Row(e.id, e.userId, e.deviceId, e.expiresAt, e.revokedAt != null));
  }

  /** Marks one token revoked (rotation or logout); an already revoked token is left as it is. */
  @Transactional
  public void revoke(UUID id, Instant now) { jpa.revoke(id, now); }

  /** Revokes every open token of the user, signing them out everywhere. */
  @Transactional
  public void revokeAll(UUID userId, Instant now) { jpa.revokeAllOf(userId, now); }

  /** Revokes every open token of one device (a device id may be missing). */
  @Transactional
  public void revokeDevice(UUID userId, String deviceId, Instant now) {
    if (deviceId == null) jpa.revokeNoDevice(userId, now);
    else jpa.revokeDevice(userId, deviceId, now);
  }
}
