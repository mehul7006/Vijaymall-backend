package com.vijay.mall.promotion.domain;

import com.vijay.mall.catalog.domain.PricedLine;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Pure discount arithmetic in paise. */
public final class CouponMath {
  private CouponMath() {}

  /**
   * True when the coupon discounts this line: the line is one the coupon is for (see {@link Coupon#covers(PricedLine)}) and,
   * unless {@code allowOnFlash}, it is not a flash sale line.
   */
  public static boolean covered(Coupon c, PricedLine line, boolean allowOnFlash) {
    return c.covers(line) && (allowOnFlash || line.flashSaleId() == null);
  }

  /** Amount the coupon applies to: the whole subtotal, or only the covered lines (flash sale lines count). */
  public static long base(Coupon c, List<PricedLine> lines, long subtotal) { return base(c, lines, subtotal, true); }

  /** Amount the coupon applies to: the sum of the covered lines only; the whole subtotal when every line is covered. */
  public static long base(Coupon c, List<PricedLine> lines, long subtotal, boolean allowOnFlash) {
    if (c.unrestricted() && allowOnFlash) return subtotal;
    return lines.stream().filter(l -> covered(c, l, allowOnFlash)).mapToLong(PricedLine::lineTotalPaise).sum();
  }

  /**
   * Discount in paise: the fixed value for FLAT, or the percentage of the base for PERCENT, capped at
   * {@code maxDiscountPaise} when set and never more than the base.
   */
  public static long discount(Coupon c, long base) {
    long d = switch (c.kind()) {
      case Coupon.FLAT -> c.value();
      case Coupon.PERCENT -> base * c.value() / 100;
      default -> 0;
    };
    if (c.maxDiscountPaise() > 0) d = Math.min(d, c.maxDiscountPaise());
    return Math.max(0, Math.min(d, base));
  }

  /**
   * Splits a coupon discount over the cart lines, one amount per line in the same order. Only lines the coupon covers
   * share it (in proportion to their price); other lines (other categories, items not listed) get 0, so a Dairy coupon never lowers a
   * Snacks line. The amounts add up to the discount exactly (capped at what the covered lines cost).
   */
  public static long[] perLine(Coupon c, List<PricedLine> lines, long discount) { return perLine(c, lines, discount, true); }

  /** Same as {@link #perLine(Coupon, List, long)}; flash sale lines are skipped unless {@code allowOnFlash}. */
  public static long[] perLine(Coupon c, List<PricedLine> lines, long discount, boolean allowOnFlash) {
    long[] weights = lines.stream().mapToLong(l -> covered(c, l, allowOnFlash) ? Math.max(0, l.lineTotalPaise()) : 0).toArray();
    long covered = Arrays.stream(weights).sum();
    long amount = Math.max(0, Math.min(discount, covered));
    if (amount == 0) return new long[lines.size()];
    return allocate(amount, weights);
  }

  /** Spreads amount over weights in proportion; the result sums to amount (largest remainder, earlier line wins a tie). */
  static long[] allocate(long amount, long[] weights) {
    long total = Arrays.stream(weights).sum();
    long[] share = new long[weights.length];
    long[] remainder = new long[weights.length];
    long given = 0;
    for (int i = 0; i < weights.length; i++) {
      long product = Math.multiplyExact(amount, weights[i]);
      share[i] = product / total;
      remainder[i] = product % total;
      given += share[i];
    }
    long left = amount - given;
    Integer[] order = IntStream.range(0, weights.length).boxed().toArray(Integer[]::new);
    Arrays.sort(order, Comparator.<Integer>comparingLong(i -> -remainder[i]).thenComparingInt(i -> i));
    for (int k = 0; k < left; k++) share[order[k]]++;
    return share;
  }
}
