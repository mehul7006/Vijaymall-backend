package com.vijay.mall.promotion.domain;

import com.vijay.mall.catalog.domain.PricedLine;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Pure discount arithmetic in paise. */
public final class CouponMath {
  private CouponMath() {}

  /** Amount the coupon applies to: the whole subtotal, or only the lines of its categories. */
  public static long base(Coupon c, List<PricedLine> lines, long subtotal) {
    if (c.allCategories()) return subtotal;
    return lines.stream().filter(l -> c.covers(l.categoryId())).mapToLong(PricedLine::lineTotalPaise).sum();
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

  /**
   * Splits a coupon discount over the cart lines, one amount per line in the same order. Only lines the coupon covers
   * share it (in proportion to their price); lines of other categories get 0, so a Dairy coupon never lowers a
   * Snacks line. The amounts add up to the discount exactly (capped at what the covered lines cost).
   */
  public static long[] perLine(Coupon c, List<PricedLine> lines, long discount) {
    long[] weights = lines.stream().mapToLong(l -> c.covers(l.categoryId()) ? Math.max(0, l.lineTotalPaise()) : 0).toArray();
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
