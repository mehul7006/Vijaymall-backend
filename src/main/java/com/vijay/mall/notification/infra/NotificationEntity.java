package com.vijay.mall.notification.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Table {@code notification}: one in-app message for a user, unread until {@code readAt} is set. */
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

  /** Required by JPA; not for application code. */
  protected NotificationEntity() {}
}
