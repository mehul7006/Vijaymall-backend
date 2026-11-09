package com.vijay.mall.promotion.domain;

import com.vijay.mall.catalog.domain.PricedLine;
import java.time.Instant;
import java.util.List;

/** priorOrders is how many earlier, non-cancelled orders the customer has (only looked up for first-order coupons). */
public record CouponContext(Coupon coupon, Instant now, long subtotalPaise, List<PricedLine> lines, int userUses,
                            int priorOrders) {
  /** A context for a customer with no earlier orders. */
  public CouponContext(Coupon coupon, Instant now, long subtotalPaise, List<PricedLine> lines, int userUses) {
    this(coupon, now, subtotalPaise, lines, userUses, 0);
  }
}
