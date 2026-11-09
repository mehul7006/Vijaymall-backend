package com.vijay.mall.promotion.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Table {@code coupon_redemption}: one use of a coupon by a customer on an order, with the discount given; {@code released} is set when the order is cancelled and the use is given back.
 */
@Entity
@Table(name = "coupon_redemption")
class CouponRedemptionEntity extends BaseEntity {
  UUID couponId;
  UUID userId;
  UUID orderId;
  long discountPaise;
  boolean released;

  /** Required by JPA; not for application code. */
  protected CouponRedemptionEntity() {}
}
