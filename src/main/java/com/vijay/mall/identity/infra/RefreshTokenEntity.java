package com.vijay.mall.identity.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_token")
class RefreshTokenEntity extends BaseEntity {
  UUID userId;
  String tokenHash;
  String deviceId;
  Instant expiresAt;
  Instant revokedAt;
  Instant createdAt;

  protected RefreshTokenEntity() {}
}
