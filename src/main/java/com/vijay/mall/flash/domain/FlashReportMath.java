package com.vijay.mall.flash.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The percentages on the flash sale reports (two decimals, half-up). */
public final class FlashReportMath {
  private FlashReportMath() {}

  /** Discount as a share of the list value of what was sold: discount / (revenue + discount). 0 when nothing was sold. */
  public static double averageDiscountPercent(long revenuePaise, long discountPaise) {
    long list = revenuePaise + discountPaise;
    return list <= 0 ? 0 : pct(discountPaise, list);
  }

  /** Units sold of the capped items as a share of their caps; null when no item has a cap. */
  public static Double sellThroughPercent(long soldOfCapped, long capTotal) {
    return capTotal <= 0 ? null : pct(Math.min(soldOfCapped, capTotal), capTotal);
  }

  private static double pct(long part, long whole) {
    return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP).doubleValue();
  }
}
