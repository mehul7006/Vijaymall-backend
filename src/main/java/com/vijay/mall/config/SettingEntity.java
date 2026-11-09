package com.vijay.mall.config;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_setting")
class SettingEntity {
  @Id String name;
  String value;
  boolean secret;
  Instant updatedAt;
  UUID updatedBy;

  protected SettingEntity() {}
}
