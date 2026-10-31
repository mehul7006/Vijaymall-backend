package com.vijay.mall.notification.infra;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationRepository {
  private final NotificationJpa jpa;

  NotificationRepository(NotificationJpa jpa) { this.jpa = jpa; }

  @Transactional
  public void insert(UUID userId, String title, String body, String kind, String ref, Instant now) {
    NotificationEntity e = new NotificationEntity();
    e.id = UUID.randomUUID();
    e.userId = userId;
    e.title = title;
    e.body = body;
    e.kind = kind;
    e.ref = ref;
    e.createdAt = now;
    jpa.save(e);
  }
}
