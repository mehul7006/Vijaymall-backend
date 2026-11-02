package com.vijay.mall.notification.infra;

import com.vijay.mall.common.Page;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationRepository {
  public record Row(UUID id, String title, String body, String kind, String ref, boolean read, Instant createdAt) {}

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

  /** Newest first. */
  public Page<Row> page(UUID userId, int page, int size) {
    var result = jpa.findByUserId(userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    List<Row> rows = result.getContent().stream()
        .map(e -> new Row(e.id, e.title, e.body, e.kind, e.ref, e.readAt != null, e.createdAt)).toList();
    return new Page<>(rows, page, size, result.getTotalElements());
  }

  public long unread(UUID userId) { return jpa.countByUserIdAndReadAtIsNull(userId); }

  public boolean owns(UUID userId, UUID id) { return jpa.existsByIdAndUserId(id, userId); }

  @Transactional
  public void markRead(UUID userId, UUID id, Instant now) { jpa.markRead(id, userId, now); }

  @Transactional
  public void markAllRead(UUID userId, Instant now) { jpa.markAllRead(userId, now); }
}
