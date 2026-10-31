package com.vijay.mall.notification.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification")
class NotificationEntity extends BaseEntity {
  UUID userId;
  String title;
  String body;
  String kind;
  String ref;
  Instant readAt;
  Instant createdAt;

  protected NotificationEntity() {}
}
