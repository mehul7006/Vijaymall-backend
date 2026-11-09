package com.vijay.mall.notification.infra;

import com.vijay.mall.common.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores the in-app notifications (the bell inbox) of users. */
@Repository
public class NotificationRepository {
  /**
   * One notification as the inbox shows it.
   * @param id notification id
   * @param title short headline
   * @param body message text
   * @param kind what it is about: {@code ORDER}, {@code PAYMENT}, {@code FLASH}, {@code STOCK} or {@code ANNOUNCEMENT} (an admin broadcast)
   * @param ref the order number, sale id or product id it points to, may be null
   * @param read true once the user opened it
   * @param createdAt when it was created
   */
  @Schema(name = "NotificationRow")
  public record Row(UUID id, String title, String body, String kind, String ref, boolean read, Instant createdAt) {}

  private final NotificationJpa jpa;

  NotificationRepository(NotificationJpa jpa) { this.jpa = jpa; }

  /** Saves a new, unread notification for the user. */
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

  /** How many notifications the user has not read yet. */
  public long unread(UUID userId) { return jpa.countByUserIdAndReadAtIsNull(userId); }

  /** True when the notification exists and belongs to the user. */
  public boolean owns(UUID userId, UUID id) { return jpa.existsByIdAndUserId(id, userId); }

  /** Marks one of the user's notifications read; ignores one that is already read. */
  @Transactional
  public void markRead(UUID userId, UUID id, Instant now) { jpa.markRead(id, userId, now); }

  /** Marks all of the user's unread notifications read. */
  @Transactional
  public void markAllRead(UUID userId, Instant now) { jpa.markAllRead(userId, now); }
}
