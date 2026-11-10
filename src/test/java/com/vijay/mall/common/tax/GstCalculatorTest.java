package com.vijay.mall.common.tax;



import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GstCalculatorTest {
  @Test void split_18Percent_of_118() {
    var s = GstCalculator.split(11800, GstRates.of(18));
    assertThat(s).isEqualTo(new TaxSplit(11800, 10000, 900, 900));
    assertThat(s.tax()).isEqualTo(1800);
  }
  @Test void split_zeroRate_hasNoTax() { assertThat(GstCalculator.split(5000, GstRates.of(0))).isEqualTo(new TaxSplit(5000, 5000, 0, 0)); }
  @Test void split_zeroAmount() { assertThat(GstCalculator.split(0, GstRates.of(18))).isEqualTo(TaxSplit.ZERO); }
  @Test void split_oddTax_goesToSgst() {
    var s = GstCalculator.split(105, GstRates.of(5)); // taxable 100, tax 5 -> 2 + 3
    assertThat(s.taxable()).isEqualTo(100);
    assertThat(s.cgst()).isEqualTo(2);
    assertThat(s.sgst()).isEqualTo(3);
  }
  @Test void split_alwaysAddsUp() {
    for (int rate : new int[] {0, 3, 5, 12, 18, 28})
      for (long g = 0; g < 3000; g += 7) {
        var s = GstCalculator.split(g, GstRates.of(rate));
        assertThat(s.taxable() + s.cgst() + s.sgst()).isEqualTo(g);
        assertThat(Math.abs(s.cgst() - s.sgst())).isLessThanOrEqualTo(1);
      }
  }
  @Test void split_negative_throws() {
    assertThatThrownBy(() -> GstCalculator.split(-1, GstRates.of(5))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GstCalculator.split(1, GstRates.of(-5))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void plus_addsFields() {
    assertThat(new TaxSplit(10, 8, 1, 1).plus(new TaxSplit(20, 16, 2, 2))).isEqualTo(new TaxSplit(30, 24, 3, 3));
  }

  @Test void allocate_proportional_andExact() {
    long[] a = GstCalculator.allocate(100, new long[] {1000, 1000, 1000});
    assertThat(Arrays.stream(a).sum()).isEqualTo(100);
    assertThat(a).containsExactly(34, 33, 33);
  }
  @Test void allocate_largestRemainderWins() {
    assertThat(GstCalculator.allocate(10, new long[] {1, 9})).containsExactly(1, 9);
    assertThat(GstCalculator.allocate(7, new long[] {5, 3, 2})).containsExactly(4, 2, 1);
  }
  @Test void allocate_zeroAmount_zeros() {
    assertThat(GstCalculator.allocate(0, new long[] {5, 5})).containsExactly(0, 0);
    assertThat(GstCalculator.allocate(0, new long[] {0, 0})).containsExactly(0, 0);
  }
  @Test void allocate_nothingToShareOver_throws() {
    assertThatThrownBy(() -> GstCalculator.allocate(5, new long[] {0, 0})).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GstCalculator.allocate(-1, new long[] {1})).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void allocate_alwaysSums() {
    long[] w = {333, 777, 1, 12345, 99};
    for (long amt = 0; amt < 500; amt += 13)
      assertThat(Arrays.stream(GstCalculator.allocate(amt, w)).sum()).isEqualTo(amt);
  }

  @Test void netAfterDiscount_reducesLines() {
    assertThat(GstCalculator.netAfterDiscount(new long[] {6000, 4000}, 1000)).containsExactly(5400, 3600);
  }
  @Test void netAfterDiscount_cappedAtSubtotal() {
    assertThat(GstCalculator.netAfterDiscount(new long[] {100, 200}, 99999)).containsExactly(0, 0);
  }
}
