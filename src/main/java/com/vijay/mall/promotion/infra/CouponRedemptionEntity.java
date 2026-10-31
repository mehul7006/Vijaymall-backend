package com.vijay.mall.promotion.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "coupon_redemption")
class CouponRedemptionEntity extends BaseEntity {
  UUID couponId;
  UUID userId;
  UUID orderId;
  long discountPaise;
  boolean released;

  protected CouponRedemptionEntity() {}
}
