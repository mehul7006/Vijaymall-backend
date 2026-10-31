package com.vijay.mall.identity.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DeviceRepository {
  private final DeviceJpa jpa;

  DeviceRepository(DeviceJpa jpa) { this.jpa = jpa; }

  /** One row per push token; a token that moves to another user is re-assigned. */
  @Transactional
  public void upsert(UUID userId, String token, String platform, Instant now) {
    DeviceTokenEntity e = jpa.findByFcmToken(token).orElseGet(() -> {
      DeviceTokenEntity n = new DeviceTokenEntity();
      n.id = UUID.randomUUID();
      n.fcmToken = token;
      n.createdAt = now;
      return n;
    });
    e.userId = userId;
    e.platform = platform;
    jpa.save(e);
  }

  public List<String> tokensOf(UUID userId) { return jpa.tokensOf(userId); }
}
