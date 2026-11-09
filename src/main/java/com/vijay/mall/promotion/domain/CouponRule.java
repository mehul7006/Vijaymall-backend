package com.vijay.mall.promotion.domain;

import java.util.Optional;

/** Specification: returns the reason code when the coupon cannot be used, empty when it is fine. */
public interface CouponRule {
  /**
   * Checks one condition.
   * @return the reason code when the coupon cannot be used, empty when the condition is met
   */
  Optional<String> violation(CouponContext c);
}
