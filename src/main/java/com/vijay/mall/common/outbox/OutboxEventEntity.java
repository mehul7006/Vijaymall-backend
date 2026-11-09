package com.vijay.mall.common.outbox;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Row of {@code outbox_event}: an event written with a business change and delivered later; {@code publishedAt} stays empty until the relay has handled it.
 */
@Entity
@Table(name = "outbox_event")
class OutboxEventEntity extends BaseEntity {
  @Column(name = "type") String eventType;
  String payload;
  Instant createdAt;
  Instant publishedAt;

  /** For JPA. */
  protected OutboxEventEntity() {}
}
