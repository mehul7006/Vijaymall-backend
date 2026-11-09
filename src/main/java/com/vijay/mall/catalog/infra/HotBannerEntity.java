package com.vijay.mall.catalog.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code hot_banner} table: a promotional banner with an optional start and end time and an active flag.
 */
@Entity
@Table(name = "hot_banner")
class HotBannerEntity extends BaseEntity {
  String title;
  String subtitle;
  String imageUrl;
  Instant startAt;
  Instant endAt;
  boolean active;

  /** No-argument constructor required by JPA; not for application code. */
  protected HotBannerEntity() {}
}
