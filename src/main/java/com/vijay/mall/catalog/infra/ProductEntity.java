package com.vijay.mall.catalog.infra;

import jakarta.persistence.Column;
import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product")
class ProductEntity extends BaseEntity {
  UUID categoryId;
  String nameEn;
  String nameGu;
  String description;
  long mrpPaise;
  long pricePaise;
  String unit;
  String packSize;
  UUID productGroupId;
  double rating;
  int reviewCount;
  String imageUrl;
  boolean active;
  boolean hotSale;
  Instant hotStart;
  Instant hotEnd;
  @Column(name = "earns_coins") boolean earnsCoins;
  @Column(name = "coin_redeemable") boolean coinRedeemable;
  @Column(name = "pos_only") boolean posOnly;
  Instant createdAt;
  Instant updatedAt;

  protected ProductEntity() {}
}
