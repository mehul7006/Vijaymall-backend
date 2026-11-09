package com.vijay.mall.promotion.infra;

import jakarta.persistence.Column;
import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Table {@code coupon}: a discount code with its kind (FLAT or PERCENT), value, limits, dates and flags. The column
 * {@code value} is the field {@code amount} here, and {@code usedCount} is the live count of uses.
 */
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

  /** Required by JPA; not for application code. */
  protected CouponEntity() {}
}
