package com.vijay.mall.identity.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Table {@code device_token}: one Firebase push token of a phone, linked to the user who is signed in on it. */
@Entity
@Table(name = "device_token")
class DeviceTokenEntity extends BaseEntity {
  UUID userId;
  String fcmToken;
  String platform;
  Instant createdAt;

  /** Required by JPA; not for application code. */
  protected DeviceTokenEntity() {}
}
