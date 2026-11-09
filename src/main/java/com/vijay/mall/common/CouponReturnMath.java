package com.vijay.mall.common;

import com.vijay.mall.order.domain.OrderReturnMath;
import java.util.Arrays;
import java.util.List;

/**
 * What a partial return refunds when the order used a coupon and coins, in paise, exact. Used for online orders and counter
 * bills alike.
 *
 * <p>Every line keeps its own numbers from the time of sale: its price {@code gross}, its share of the coupon discount
 * {@code coupon} (0 on lines the coupon did not cover) and {@code basis}, what the customer really paid for the line (price less
 * coupon share less coin share, and less any manual discount at the counter). With the default policy {@code KEEP} a return of
 * some pieces of a line refunds that line's {@code basis} in proportion: the cumulative share after the return minus the
 * cumulative share before it, half up, so the last piece takes the remainder and any sequence of returns adds up to exactly the
 * basis. The delivery fee is never part of it.
 *
 * <p>With {@code CLAWBACK_BELOW_MINIMUM}, once what is left of the covered lines falls below the coupon's minimum order, the
 * coupon discount sitting on the pieces still kept (their share of the coupon, cumulative half up) is taken out of the refund of
 * the return that caused it, never more than that refund. From then on the kept lines carry no coupon share: as their pieces come
 * back they are refunded the clawed amount in proportion (so after everything is returned the customer has got back exactly the
 * total basis). The state is rebuilt from the earlier returns, so nothing is stored except what each return clawed back.
 */
public final class CouponReturnMath {
  private CouponReturnMath() {}

  /** Policy name that keeps the coupon benefit (the default). */
  public static final String KEEP = "KEEP";
  /** Policy name that takes the coupon benefit back when the covered value falls below the coupon's minimum. */
  public static final String CLAWBACK = "CLAWBACK_BELOW_MINIMUM";

  /** One line of the sale: how many pieces, the price of all of them, the coupon share on the line and what was paid for it. */
  public record Line(int qty, long grossPaise, long couponPaise, long basisPaise) {}

  /** An earlier return: pieces returned per line (same order as the lines) and the coupon amount it clawed back. */
  public record Earlier(int[] returned, long clawbackPaise) {}

  /**
   * What a return refunds, per line, in the same order as the lines.
   * The identity {@code refund = gross - couponShare - otherShare - clawback} holds for every line: couponShare is the coupon
   * discount that leaves with the pieces (less any that was taken back earlier and is now given back), otherShare the coins (and
   * counter discount) that leave with them.
   */
  public record Plan(long[] grossPaise, long[] couponSharePaise, long[] otherSharePaise, long[] clawbackPaise,
                     long[] refundPaise, long clawbackTotalPaise, boolean clawbackNow) {}

  /** The part of the coupon discount still sitting on the pieces that are not back yet, per line (cumulative, half up). */
  static long[] remainingCoupon(List<Line> lines, int[] returned) {
    long[] out = new long[lines.size()];
    for (int i = 0; i < out.length; i++) {
      Line l = lines.get(i);
      out[i] = l.couponPaise() - OrderReturnMath.cumulative(l.couponPaise(), returned[i], l.qty());
    }
    return out;
  }

  /** The price of the covered pieces (lines with a coupon share) that are not back yet. */
  static long remainingCovered(List<Line> lines, int[] returned) {
    long sum = 0;
    for (int i = 0; i < lines.size(); i++) {
      Line l = lines.get(i);
      if (l.couponPaise() > 0) sum += l.grossPaise() - OrderReturnMath.cumulative(l.grossPaise(), returned[i], l.qty());
    }
    return sum;
  }

  /**
   * Works out the refund of a return.
   *
   * @param lines the lines of the sale
   * @param before pieces already returned per line
   * @param earlier the returns made before, oldest first
   * @param returning pieces coming back now per line (0 for lines not in this return)
   * @param policy {@code KEEP} or {@code CLAWBACK_BELOW_MINIMUM}; anything else is taken as {@code KEEP}
   * @param minOrderPaise the coupon's minimum order kept with the sale (0 = none, nothing is ever clawed back)
   */
  public static Plan plan(List<Line> lines, int[] before, List<Earlier> earlier, int[] returning, String policy, long minOrderPaise) {
    int n = lines.size();
    long[] keepRefund = new long[n];
    long[] gross = new long[n];
    long[] couponKeep = new long[n];
    long[] other = new long[n];
    for (int i = 0; i < n; i++) {
      Line l = lines.get(i);
      gross[i] = OrderReturnMath.share(l.grossPaise(), l.qty(), before[i], returning[i]);
      couponKeep[i] = OrderReturnMath.share(l.couponPaise(), l.qty(), before[i], returning[i]);
      keepRefund[i] = OrderReturnMath.share(l.basisPaise(), l.qty(), before[i], returning[i]);
      other[i] = gross[i] - couponKeep[i] - keepRefund[i];
    }
    long[] couponShare = couponKeep.clone();
    long[] refund = keepRefund.clone();
    long[] claw = new long[n];
    long clawTotal = 0;
    boolean clawNow = false;
    boolean policyOn = CLAWBACK.equals(policy) && minOrderPaise > 0 && lines.stream().anyMatch(l -> l.couponPaise() > 0);
    if (policyOn) {
      int[] cumulative = new int[n];
      int triggeredAt = -1;
      for (int j = 0; j < earlier.size() && triggeredAt < 0; j++) {
        for (int i = 0; i < n; i++) cumulative[i] += earlier.get(j).returned()[i];
        if (remainingCovered(lines, cumulative) < minOrderPaise) triggeredAt = j;
      }
      if (triggeredAt >= 0) {
        long[] due = remainingCoupon(lines, cumulative);
        long[] given = given(due, earlier.get(triggeredAt).clawbackPaise());
        for (int i = 0; i < n; i++) {
          Line l = lines.get(i);
          int stillOut = l.qty() - cumulative[i];
          if (stillOut <= 0 || returning[i] == 0) continue;
          long back = OrderReturnMath.cumulative(given[i], before[i] + returning[i] - cumulative[i], stillOut)
              - OrderReturnMath.cumulative(given[i], before[i] - cumulative[i], stillOut);
          refund[i] += back;
          couponShare[i] -= back;
        }
      } else {
        int[] after = new int[n];
        for (int i = 0; i < n; i++) after[i] = before[i] + returning[i];
        if (remainingCovered(lines, after) < minOrderPaise) {
          long due = Arrays.stream(remainingCoupon(lines, after)).sum();
          long refundSum = Arrays.stream(refund).sum();
          clawTotal = Math.min(due, refundSum);
          claw = OrderReturnMath.allocate(clawTotal, refund);
          for (int i = 0; i < n; i++) refund[i] -= claw[i];
          clawNow = true;
        }
      }
    }
    return new Plan(gross, couponShare, other, claw, refund, clawTotal, clawNow);
  }

  /** What was really taken back per line: the whole due amount, or its proportional part when the refund was too small to hold it. */
  private static long[] given(long[] due, long collected) {
    long total = Arrays.stream(due).sum();
    return collected >= total ? due : OrderReturnMath.allocate(collected, due);
  }
}
