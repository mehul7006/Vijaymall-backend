package com.vijay.mall.catalog.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hot_banner")
class HotBannerEntity extends BaseEntity {
  String title;
  String subtitle;
  String imageUrl;
  Instant startAt;
  Instant endAt;
  boolean active;

  protected HotBannerEntity() {}
}
