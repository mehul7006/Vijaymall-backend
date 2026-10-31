package com.vijay.mall.identity.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_token")
class DeviceTokenEntity extends BaseEntity {
  UUID userId;
  String fcmToken;
  String platform;
  Instant createdAt;

  protected DeviceTokenEntity() {}
}
