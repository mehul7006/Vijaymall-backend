package com.vijay.mall.promotion.infra;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key (coupon, category) of {@code coupon_category}. */
class CouponCategoryId implements Serializable {
  UUID couponId;
  UUID categoryId;

  CouponCategoryId() {}

  CouponCategoryId(UUID couponId, UUID categoryId) {
    this.couponId = couponId;
    this.categoryId = categoryId;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CouponCategoryId other && Objects.equals(couponId, other.couponId)
        && Objects.equals(categoryId, other.categoryId);
  }

  @Override
  public int hashCode() { return Objects.hash(couponId, categoryId); }
}
