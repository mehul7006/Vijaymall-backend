package com.vijay.mall.catalog.infra;

import jakarta.persistence.Column;
import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code product} table. Each row is one sellable variant; variants of one product share
 * {@code productGroupId}. Prices are integer paise, {@code rating} / {@code reviewCount} are copied from the reviews, and
 * {@code posOnly} hides the item from the online shop. Stock is not stored here (see the inventory module).
 */
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
  String variantName = "";
  int variantOrder;
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

  /** No-argument constructor required by JPA; not for application code. */
  protected ProductEntity() {}
}
