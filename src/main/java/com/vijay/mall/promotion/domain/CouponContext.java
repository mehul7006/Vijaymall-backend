package com.vijay.mall.promotion.domain;

import com.vijay.mall.catalog.domain.PricedLine;
import java.time.Instant;
import java.util.List;

/**
 * priorOrders is how many earlier, non-cancelled orders the customer has (only looked up for first-order coupons).
 * allowOnFlash is the admin setting {@code coupon.allowOnFlashItems}: whether flash sale lines can be discounted by the coupon.
 */
public record CouponContext(Coupon coupon, Instant now, long subtotalPaise, List<PricedLine> lines, int userUses,
                            int priorOrders, boolean allowOnFlash) {
  /** A context where a coupon also applies to flash sale lines. */
  public CouponContext(Coupon coupon, Instant now, long subtotalPaise, List<PricedLine> lines, int userUses, int priorOrders) {
    this(coupon, now, subtotalPaise, lines, userUses, priorOrders, true);
  }

  /** True when the coupon discounts this line (see {@link CouponMath#covered}). */
  public boolean covered(PricedLine line) { return CouponMath.covered(coupon, line, allowOnFlash); }

  /** The sum of the covered lines. */
  public long coveredPaise() { return CouponMath.base(coupon, lines, subtotalPaise, allowOnFlash); }

  /** A context for a customer with no earlier orders. */
  public CouponContext(Coupon coupon, Instant now, long subtotalPaise, List<PricedLine> lines, int userUses) {
    this(coupon, now, subtotalPaise, lines, userUses, 0);
  }
}
