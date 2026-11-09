package com.vijay.mall.identity.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores the push tokens of customers' phones. */
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

  /** All push tokens registered to the user. */
  public List<String> tokensOf(UUID userId) { return jpa.tokensOf(userId); }

  /** Removes all of the user's push tokens (account deletion). */
  @Transactional
  public void deleteAll(UUID userId) { jpa.deleteAllOf(userId); }

  /** Forgets one push token, but only if it is registered to this user (sign-out); nothing happens otherwise. */
  @Transactional
  public void deleteOwned(UUID userId, String token) { jpa.deleteOwnedToken(userId, token); }

  /** Forgets one push token (Firebase said the app was uninstalled or the token is no longer valid). */
  @Transactional
  public void deleteToken(String token) { jpa.deleteByToken(token); }
}
