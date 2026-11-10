package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The decimal-rate GST maths must give the same paise as the earlier whole-percent maths for every whole rate, and the right paise for
 * 0.25, 1.5 and 40. {@code Old} below is a verbatim copy of the int-based {@code GstCalculator.split} and
 * {@code BillCalculator.build} from before the rates became decimal; it is kept only to compare against.
 */
class GstDecimalComparisonTest {
  private static final int[] OLD_RATES = {0, 3, 5, 12, 18, 28};

  /** The previous int-based calculation, unchanged. */
  static final class Old {
    static TaxSplit split(long gross, int gstPercent) {
      long taxable = Math.floorDiv(gross * 100 * 2 + (100 + gstPercent), 2L * (100 + gstPercent));
      long tax = gross - taxable;
      long cgst = tax / 2;
      return new TaxSplit(gross, taxable, cgst, tax - cgst);
    }

    record Draft(long linePaise, int gstPercent) { }

    /** The earlier build with one overall discount: returns {taxable, cgst, sgst, total} of the whole bill. */
    static long[] build(List<Draft> drafts, long discount) {
      long[] gross = drafts.stream().mapToLong(Draft::linePaise).toArray();
      long subtotal = Arrays.stream(gross).sum();
      long applied = Math.max(0, Math.min(discount, subtotal));
      long[] net = GstCalculator.netAfterDiscount(gross, applied);
      long taxable = 0;
      long cgst = 0;
      long sgst = 0;
      for (int i = 0; i < gross.length; i++) {
        TaxSplit t = split(net[i], drafts.get(i).gstPercent());
        taxable += t.taxable();
        cgst += t.cgst();
        sgst += t.sgst();
      }
      return new long[] {taxable, cgst, sgst, subtotal - applied};
    }
  }

  @Test void splitEqualsTheOldMathForEveryWholeRateOverAGrid() {
    long checked = 0;
    for (int rate : OLD_RATES) {
      for (long net = 0; net <= 100_000; net += 1) {
        assertThat(GstCalculator.split(net, GstRates.of(rate))).as("net %d rate %d", net, rate).isEqualTo(Old.split(net, rate));
        checked++;
      }
    }
    Random random = new Random(20250922);
    for (int i = 0; i < 300_000; i++) {
      long net = (long) (random.nextDouble() * random.nextDouble() * 5_000_000_000L);
      int rate = OLD_RATES[random.nextInt(OLD_RATES.length)];
      assertThat(GstCalculator.split(net, GstRates.of(rate))).isEqualTo(Old.split(net, rate));
      checked++;
    }
    assertThat(checked).isEqualTo(6 * 100_001 + 300_000);
  }

  @Test void splitTreatsTrailingZerosAsTheSameRate() {
    for (long net = 0; net <= 20_000; net += 13) {
      assertThat(GstCalculator.split(net, new BigDecimal("18.00"))).isEqualTo(Old.split(net, 18));
      assertThat(GstCalculator.split(net, new BigDecimal("5.0"))).isEqualTo(Old.split(net, 5));
      assertThat(GstCalculator.split(net, new BigDecimal("1.500"))).isEqualTo(GstCalculator.split(net, new BigDecimal("1.5")));
    }
  }

  @Test void billsEqualTheOldMathForRandomBasketsDiscountsAndRoundOff() {
    Random random = new Random(42);
    for (int round = 0; round < 40_000; round++) {
      int lines = 1 + random.nextInt(6);
      List<BillCalculator.Draft> drafts = new ArrayList<>();
      List<Old.Draft> old = new ArrayList<>();
      for (int i = 0; i < lines; i++) {
        int qty = 1 + random.nextInt(12);
        long price = random.nextInt(250_000);
        int rate = OLD_RATES[random.nextInt(OLD_RATES.length)];
        drafts.add(new BillCalculator.Draft(UUID.randomUUID(), "x", SoldBy.UNIT, qty, price, GstRates.of(rate), ""));
        old.add(new Old.Draft(qty * price, rate));
      }
      long subtotal = drafts.stream().mapToLong(BillCalculator.Draft::linePaise).sum();
      long discount = switch (random.nextInt(4)) {
        case 0 -> 0;
        case 1 -> random.nextInt((int) Math.min(subtotal + 1, Integer.MAX_VALUE));
        case 2 -> subtotal;
        default -> subtotal + random.nextInt(1000);
      };
      BillCalculator.Bill bill = BillCalculator.build(drafts, discount);
      long[] expected = Old.build(old, discount);
      assertThat(bill.tax().taxable()).isEqualTo(expected[0]);
      assertThat(bill.tax().cgst()).isEqualTo(expected[1]);
      assertThat(bill.tax().sgst()).isEqualTo(expected[2]);
      assertThat(bill.totalPaise()).isEqualTo(expected[3]);
      for (boolean roundOff : new boolean[] {false, true}) {
        assertThat(payable(bill.totalPaise(), roundOff)).isEqualTo(payable(expected[3], roundOff));
      }
    }
  }

  @Test void billsWithPerLineCouponSharesEqualTheOldMath() {
    Random random = new Random(7);
    for (int round = 0; round < 10_000; round++) {
      int lines = 1 + random.nextInt(5);
      List<BillCalculator.Draft> drafts = new ArrayList<>();
      long[] coupon = new long[lines];
      long[] gross = new long[lines];
      int[] rates = new int[lines];
      for (int i = 0; i < lines; i++) {
        rates[i] = OLD_RATES[random.nextInt(OLD_RATES.length)];
        long price = 1 + random.nextInt(90_000);
        int qty = 1 + random.nextInt(5);
        drafts.add(new BillCalculator.Draft(UUID.randomUUID(), "x", SoldBy.UNIT, qty, price, GstRates.of(rates[i]), ""));
        gross[i] = price * qty;
        coupon[i] = random.nextBoolean() ? 0 : random.nextLong(gross[i] + 1);
      }
      long other = random.nextInt(5000);
      BillCalculator.Bill bill = BillCalculator.build(drafts, coupon, other);
      long[] afterCoupon = IntStream.range(0, lines).mapToLong(i -> gross[i] - coupon[i]).toArray();
      long[] net = GstCalculator.netAfterDiscount(afterCoupon, Math.min(other, Arrays.stream(afterCoupon).sum()));
      for (int i = 0; i < lines; i++) {
        assertThat(bill.lines().get(i).tax()).isEqualTo(Old.split(net[i], rates[i]));
        assertThat(bill.lines().get(i).netPaise()).isEqualTo(net[i]);
      }
    }
  }

  private static long payable(long total, boolean roundOff) { return roundOff ? Math.floorDiv(total + 50, 100) * 100 : total; }

  /*
   * Hand-computed expectations. taxable = round half up of gross * 100 / (100 + rate); tax = gross - taxable; CGST = tax / 2 rounded
   * down and SGST = the rest.
   * 0.25%: 10025 * 100 / 100.25 = 10000 exactly -> tax 25 -> CGST 12, SGST 13.
   *        1000 * 100 / 100.25 = 997.506 -> 998 -> tax 2 -> 1 + 1.
   * 1.5%:  10150 * 100 / 101.5 = 10000 exactly -> tax 150 -> 75 + 75.
   *        1000 * 100 / 101.5 = 985.222 -> 985 -> tax 15 -> CGST 7, SGST 8.
   * 40%:   14000 * 100 / 140 = 10000 exactly -> tax 4000 -> 2000 + 2000.
   *        999 * 100 / 140 = 713.571 -> 714 -> tax 285 -> CGST 142, SGST 143.
   *        1 * 100 / 140 = 0.714 -> 1 -> tax 0.
   */
  @Test void quarterPercentRateIsSplitAsComputedByHand() {
    assertThat(GstCalculator.split(10025, new BigDecimal("0.25"))).isEqualTo(new TaxSplit(10025, 10000, 12, 13));
    assertThat(GstCalculator.split(1000, new BigDecimal("0.25"))).isEqualTo(new TaxSplit(1000, 998, 1, 1));
  }

  @Test void oneAndAHalfPercentRateIsSplitAsComputedByHand() {
    assertThat(GstCalculator.split(10150, new BigDecimal("1.5"))).isEqualTo(new TaxSplit(10150, 10000, 75, 75));
    assertThat(GstCalculator.split(1000, new BigDecimal("1.5"))).isEqualTo(new TaxSplit(1000, 985, 7, 8));
  }

  @Test void fortyPercentRateIsSplitAsComputedByHand() {
    assertThat(GstCalculator.split(14000, new BigDecimal("40"))).isEqualTo(new TaxSplit(14000, 10000, 2000, 2000));
    assertThat(GstCalculator.split(999, new BigDecimal("40"))).isEqualTo(new TaxSplit(999, 714, 142, 143));
    assertThat(GstCalculator.split(1, new BigDecimal("40"))).isEqualTo(new TaxSplit(1, 1, 0, 0));
  }

  @Test void newRatesAlwaysAddUpAndKeepHalvesWithinOnePaisa() {
    for (String rate : new String[] {"0.25", "1.5", "40"}) {
      for (long gross = 0; gross <= 50_000; gross += 3) {
        TaxSplit s = GstCalculator.split(gross, new BigDecimal(rate));
        assertThat(s.taxable() + s.cgst() + s.sgst()).isEqualTo(gross);
        assertThat(s.sgst() - s.cgst()).isBetween(0L, 1L);
      }
    }
  }

  @Test void negativeAmountOrRateIsRefused() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> GstCalculator.split(-1, GstRates.of(5))).isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> GstCalculator.split(1, new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
  }
}
