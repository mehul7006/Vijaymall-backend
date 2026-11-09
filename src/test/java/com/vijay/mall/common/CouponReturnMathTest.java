package com.vijay.mall.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.CouponReturnMath.Earlier;
import com.vijay.mall.common.CouponReturnMath.Line;
import com.vijay.mall.common.CouponReturnMath.Plan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The refund of partial returns under both coupon policies, including random sequences of returns. */
class CouponReturnMathTest {
  private static final String CB = CouponReturnMath.CLAWBACK;

  /** 3 lines of the 1000 order: 400 (qty 2), 350 (qty 1) uncovered, 250 (qty 3) covered; 10 percent of the covered 650 = 65. */
  private static List<Line> sold() {
    return List.of(new Line(2, 40000, 4000, 36000 - 100), new Line(1, 35000, 0, 35000), new Line(3, 25000, 2500, 22500 - 33));
  }

  private static long sum(long[] a) { return Arrays.stream(a).sum(); }

  @Test
  void anUncoveredLineComesBackAtItsFullPriceAndACoveredOneNetOfItsShare() {
    Plan p = CouponReturnMath.plan(sold(), new int[3], List.of(), new int[] {0, 1, 0}, CouponReturnMath.KEEP, 0);
    assertThat(p.refundPaise()).containsExactly(0, 35000, 0);
    assertThat(p.couponSharePaise()[1]).isZero();
    Plan q = CouponReturnMath.plan(sold(), new int[3], List.of(), new int[] {1, 0, 0}, CouponReturnMath.KEEP, 0);
    assertThat(q.couponSharePaise()[0]).isEqualTo(2000);
    assertThat(q.refundPaise()[0] + q.couponSharePaise()[0] + q.otherSharePaise()[0]).isEqualTo(q.grossPaise()[0]);
    assertThat(q.clawbackTotalPaise()).isZero();
  }

  @Test
  void keepAddsUpToTheNetForAnySequenceOfReturns() {
    Random random = new Random(7);
    for (int round = 0; round < 300; round++) {
      List<Line> lines = randomLines(random);
      assertThat(runSequence(lines, random, CouponReturnMath.KEEP, 0).refunded())
          .isEqualTo(lines.stream().mapToLong(Line::basisPaise).sum());
    }
  }

  @Test
  void clawbackRefundsTheWholeNetOnceEverythingIsBackAndNeverGoesNegative() {
    Random random = new Random(11);
    int triggered = 0;
    for (int round = 0; round < 500; round++) {
      List<Line> lines = randomLines(random);
      long covered = lines.stream().filter(l -> l.couponPaise() > 0).mapToLong(Line::grossPaise).sum();
      Result r = runSequence(lines, random, CB, Math.max(1, covered - random.nextInt((int) Math.max(1, covered / 2) + 1)));
      assertThat(r.refunded()).isEqualTo(lines.stream().mapToLong(Line::basisPaise).sum());
      assertThat(r.minRefund()).isGreaterThanOrEqualTo(0);
      if (r.clawed() > 0) triggered++;
    }
    assertThat(triggered).isPositive();
  }

  @Test
  void clawbackTakesTheKeptShareOutOfTheReturnThatCausesIt() {
    // covered 2 x 500 = 1000 gross with 100 coupon, minimum 1000: returning one drops the covered value to 500
    List<Line> lines = List.of(new Line(2, 100000, 10000, 90000), new Line(1, 35000, 0, 35000));
    Plan first = CouponReturnMath.plan(lines, new int[2], List.of(), new int[] {1, 0}, CB, 100000);
    assertThat(first.clawbackNow()).isTrue();
    assertThat(first.clawbackTotalPaise()).isEqualTo(5000);
    assertThat(first.refundPaise()[0]).isEqualTo(45000 - 5000);
    assertThat(first.clawbackPaise()[0]).isEqualTo(5000);
    Plan second = CouponReturnMath.plan(lines, new int[] {1, 0}, List.of(new Earlier(new int[] {1, 0}, 5000)), new int[] {1, 0}, CB, 100000);
    assertThat(second.clawbackNow()).isFalse();
    assertThat(second.refundPaise()[0]).isEqualTo(45000 + 5000);
    assertThat(first.refundPaise()[0] + second.refundPaise()[0]).isEqualTo(90000);
    Plan keep = CouponReturnMath.plan(lines, new int[2], List.of(), new int[] {1, 0}, CouponReturnMath.KEEP, 100000);
    assertThat(keep.refundPaise()[0]).isEqualTo(45000);
    assertThat(keep.clawbackTotalPaise()).isZero();
  }

  @Test
  void clawbackIsLimitedToTheRefundAndTheRestIsForgiven() {
    List<Line> lines = List.of(new Line(2, 100000, 90000, 10000), new Line(1, 100, 0, 100));
    Plan first = CouponReturnMath.plan(lines, new int[2], List.of(), new int[] {0, 1}, CB, 100000);
    assertThat(first.clawbackNow()).as("the covered line is untouched, so nothing is below the minimum").isFalse();
    Plan one = CouponReturnMath.plan(lines, new int[2], List.of(), new int[] {1, 0}, CB, 100000);
    assertThat(one.refundPaise()[0]).isZero();
    assertThat(one.clawbackTotalPaise()).isEqualTo(5000);
    Plan next = CouponReturnMath.plan(lines, new int[] {1, 0}, List.of(new Earlier(new int[] {1, 0}, 5000)), new int[] {1, 0}, CB, 100000);
    assertThat(next.refundPaise()[0]).isGreaterThanOrEqualTo(0);
  }

  @Test
  void aPartialClawbackGivesBackOnlyWhatWasTaken() {
    List<Line> lines = List.of(new Line(3, 90000, 9000, 40000));
    Plan first = CouponReturnMath.plan(lines, new int[1], List.of(), new int[] {1}, CB, 90000);
    long taken = first.clawbackTotalPaise();
    assertThat(taken).isEqualTo(Math.min(6000, first.refundPaise()[0] + taken));
    Plan second = CouponReturnMath.plan(lines, new int[] {1}, List.of(new Earlier(new int[] {1}, taken / 2)), new int[] {2}, CB, 90000);
    assertThat(second.refundPaise()[0]).isGreaterThan(0);
  }

  @Test
  void policyOffOrNoMinimumOrNoCouponNeverClaws() {
    List<Line> lines = List.of(new Line(2, 1000, 100, 900));
    assertThat(CouponReturnMath.plan(lines, new int[1], List.of(), new int[] {1}, CB, 0).clawbackNow()).isFalse();
    assertThat(CouponReturnMath.plan(lines, new int[1], List.of(), new int[] {1}, "other", 2000).clawbackNow()).isFalse();
    assertThat(CouponReturnMath.plan(List.of(new Line(2, 1000, 0, 1000)), new int[1], List.of(), new int[] {1}, CB, 2000).clawbackNow()).isFalse();
    assertThat(CouponReturnMath.plan(lines, new int[1], List.of(), new int[] {2}, CB, 2000).clawbackTotalPaise()).isZero();
  }

  private record Result(long refunded, long clawed, long minRefund) {}

  private static List<Line> randomLines(Random random) {
    List<Line> lines = new ArrayList<>();
    int n = 1 + random.nextInt(4);
    for (int i = 0; i < n; i++) {
      int qty = 1 + random.nextInt(5);
      long gross = 100L * qty + random.nextInt(5000);
      long coupon = random.nextBoolean() ? random.nextInt((int) gross / 2 + 1) : 0;
      long coins = random.nextInt((int) (gross - coupon) / 3 + 1);
      lines.add(new Line(qty, gross, coupon, gross - coupon - coins));
    }
    return lines;
  }

  /** Returns every piece in random chunks and adds up the refunds. */
  private static Result runSequence(List<Line> lines, Random random, String policy, long min) {
    int n = lines.size();
    int[] before = new int[n];
    List<Earlier> earlier = new ArrayList<>();
    long refunded = 0;
    long clawed = 0;
    long minRefund = Long.MAX_VALUE;
    while (remaining(lines, before) > 0) {
      int[] now = new int[n];
      boolean any = false;
      for (int i = 0; i < n; i++) {
        int left = lines.get(i).qty() - before[i];
        if (left > 0 && random.nextInt(3) > 0) {
          now[i] = 1 + random.nextInt(left);
          any = true;
        }
      }
      if (!any) continue;
      Plan p = CouponReturnMath.plan(lines, before.clone(), earlier, now, policy, min);
      for (int i = 0; i < n; i++) {
        assertThat(p.refundPaise()[i]).isGreaterThanOrEqualTo(0);
        assertThat(p.refundPaise()[i]).isEqualTo(p.grossPaise()[i] - p.couponSharePaise()[i] - p.otherSharePaise()[i] - p.clawbackPaise()[i]);
        minRefund = Math.min(minRefund, p.refundPaise()[i]);
      }
      refunded += sum(p.refundPaise());
      clawed += p.clawbackTotalPaise();
      assertThat(sum(p.clawbackPaise())).isEqualTo(p.clawbackTotalPaise());
      earlier.add(new Earlier(now, p.clawbackTotalPaise()));
      for (int i = 0; i < n; i++) before[i] += now[i];
    }
    return new Result(refunded, clawed, minRefund);
  }

  private static int remaining(List<Line> lines, int[] before) {
    int r = 0;
    for (int i = 0; i < lines.size(); i++) r += lines.get(i).qty() - before[i];
    return r;
  }
}
