package com.vijay.mall.common.outbox;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "outbox_event")
class OutboxEventEntity extends BaseEntity {
  @Column(name = "type") String eventType;
  String payload;
  Instant createdAt;
  Instant publishedAt;

  protected OutboxEventEntity() {}
}
