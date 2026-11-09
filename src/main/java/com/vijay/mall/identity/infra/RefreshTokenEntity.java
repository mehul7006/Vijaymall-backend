package com.vijay.mall.identity.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Table {@code refresh_token}: the SHA-256 hash of an issued refresh token with its device, expiry and revocation time.
 */
@Entity
@Table(name = "refresh_token")
class RefreshTokenEntity extends BaseEntity {
  UUID userId;
  String tokenHash;
  String deviceId;
  Instant expiresAt;
  Instant revokedAt;
  Instant createdAt;

  /** Required by JPA; not for application code. */
  protected RefreshTokenEntity() {}
}
