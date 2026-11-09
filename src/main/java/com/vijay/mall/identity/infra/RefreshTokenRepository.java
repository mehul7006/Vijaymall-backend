package com.vijay.mall.identity.infra;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RefreshTokenRepository {
  public record Row(UUID id, UUID userId, String deviceId, Instant expiresAt, boolean revoked) {}

  private final RefreshTokenJpa jpa;

  RefreshTokenRepository(RefreshTokenJpa jpa) { this.jpa = jpa; }

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

  public Optional<Row> findByHash(String hash) {
    return jpa.findByTokenHash(hash).map(e -> new Row(e.id, e.userId, e.deviceId, e.expiresAt, e.revokedAt != null));
  }

  @Transactional
  public void revoke(UUID id, Instant now) { jpa.revoke(id, now); }

  @Transactional
  public void revokeAll(UUID userId, Instant now) { jpa.revokeAllOf(userId, now); }

  /** Revokes every open token of one device (a device id may be missing). */
  @Transactional
  public void revokeDevice(UUID userId, String deviceId, Instant now) {
    if (deviceId == null) jpa.revokeNoDevice(userId, now);
    else jpa.revokeDevice(userId, deviceId, now);
  }
}
