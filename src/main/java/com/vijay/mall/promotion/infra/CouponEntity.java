package com.vijay.mall.promotion.infra;

import jakarta.persistence.Column;
import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coupon")
class CouponEntity extends BaseEntity {
  String code;
  String kind;
  @Column(name = "value") long amount;
  long minOrderPaise;
  long maxDiscountPaise;
  UUID categoryId;
  int perUserLimit;
  int totalLimit;
  int usedCount;
  Instant startAt;
  Instant endAt;
  boolean active;
  boolean worksWithCoins = true;
  boolean firstOrderOnly;

  protected CouponEntity() {}
}
