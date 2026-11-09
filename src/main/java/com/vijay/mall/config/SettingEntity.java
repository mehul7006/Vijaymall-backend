package com.vijay.mall.config;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Row of {@code app_setting}: one setting the admin saved; the value is encrypted when {@code secret} is true. */
@Entity
@Table(name = "app_setting")
class SettingEntity {
  @Id String name;
  String value;
  boolean secret;
  Instant updatedAt;
  UUID updatedBy;

  /** For JPA. */
  protected SettingEntity() {}
}
