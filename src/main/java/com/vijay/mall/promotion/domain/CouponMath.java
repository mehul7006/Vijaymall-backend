package com.vijay.mall.promotion.domain;

import com.vijay.mall.catalog.domain.PricedLine;
import java.util.List;

/** Pure discount arithmetic in paise. */
public final class CouponMath {
  private CouponMath() {}

  /** Amount the coupon applies to: the whole subtotal, or only the lines of its category. */
  public static long base(Coupon c, List<PricedLine> lines, long subtotal) {
    if (c.categoryId() == null) return subtotal;
    return lines.stream().filter(l -> c.categoryId().equals(l.categoryId())).mapToLong(PricedLine::lineTotalPaise).sum();
  }

  public static long discount(Coupon c, long base) {
    long d = switch (c.kind()) {
      case Coupon.FLAT -> c.value();
      case Coupon.PERCENT -> base * c.value() / 100;
      default -> 0;
    };
    if (c.maxDiscountPaise() > 0) d = Math.min(d, c.maxDiscountPaise());
    return Math.max(0, Math.min(d, base));
  }
}
