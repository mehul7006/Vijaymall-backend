package com.vijay.mall.common.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class GstSlabsTest {
  @Test
  void groupsByRateNumericallySortedLowestFirst() {
    var slabs = GstSlabs.of(List.of(new BigDecimal("18.00"), GstRates.of(5), GstRates.of(18), GstRates.of("0.25"), GstRates.of(5)),
        List.of(GstCalculator.split(11800, GstRates.of(18)), GstCalculator.split(10500, GstRates.of(5)), GstCalculator.split(1180, GstRates.of(18)),
            GstCalculator.split(10025, GstRates.of("0.25")), GstCalculator.split(105, GstRates.of(5))));
    assertThat(slabs).extracting(s -> s.gstPercent().toPlainString()).containsExactly("0.25", "5", "18");
    assertThat(slabs.get(1)).isEqualTo(new GstSlab(GstRates.of(5), 10100, 252, 253, 505));
    assertThat(slabs.get(2)).isEqualTo(new GstSlab(GstRates.of(18), 11000, 990, 990, 1980));
    assertThat(slabs.get(0).cgstPaise()).isEqualTo(12);
    assertThat(slabs.get(0).sgstPaise()).isEqualTo(13);
  }

  @Test
  void totalAddsTheSlabsUp() {
    var slabs = GstSlabs.of(List.of(GstRates.of(5), GstRates.of(18)),
        List.of(GstCalculator.split(10500, GstRates.of(5)), GstCalculator.split(11800, GstRates.of(18))));
    assertThat(GstSlabs.total(slabs)).isEqualTo(new TaxSplit(22300, 20000, 1150, 1150));
    assertThat(GstSlabs.total(List.of())).isEqualTo(TaxSplit.ZERO);
    assertThat(GstSlabs.of(List.of(), List.of())).isEmpty();
  }

  @Test
  void netPerLineWithACouponSplitTakesTheCouponOffItsLinesThenSpreadsTheRest() {
    long[] net = GstCalculator.netPerLine(new long[] {11800, 10500}, new long[] {1800, 0}, 1800, 1000);
    assertThat(net).containsExactly(10000 - 488, 10500 - 512);
  }

  @Test
  void netPerLineCapsACouponShareAtItsLineAndTheOtherDiscountAtWhatIsLeft() {
    assertThat(GstCalculator.netPerLine(new long[] {300, 500}, new long[] {900, -5}, 895, 100000)).containsExactly(0, 0);
    assertThat(GstCalculator.netPerLine(new long[] {300, 500}, new long[] {100, 0}, 100, 0)).containsExactly(200, 500);
  }

  @Test
  void netPerLineWithoutAUsableSplitSpreadsTheWholeDiscount() {
    long[] gross = {5000, 5000};
    assertThat(GstCalculator.netPerLine(gross, null, 1000, 1)).containsExactly(4500 - 1, 4500);
    assertThat(GstCalculator.netPerLine(gross, new long[] {1000}, 1000, 0)).containsExactly(4500, 4500);
    assertThat(GstCalculator.netPerLine(gross, new long[] {300, 300}, 1000, 0)).containsExactly(4500, 4500);
    assertThat(GstCalculator.netPerLine(gross, null, 20000, 20000)).containsExactly(0, 0);
  }

  @Test
  void productGstDefaultIsNotConfigured() {
    assertThat(ProductGst.UNSET.configured()).isFalse();
    assertThat(ProductGst.UNSET.gstPercent()).isEqualByComparingTo("0");
    assertThat(ProductGst.UNSET.hsn()).isEmpty();
  }
}
