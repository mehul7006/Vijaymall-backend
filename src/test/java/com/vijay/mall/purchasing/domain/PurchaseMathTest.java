package com.vijay.mall.purchasing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PurchaseMathTest {
  private static PurchaseMath.LineIn line(int qty, int free, long unit, int gst) {
    return new PurchaseMath.LineIn(qty, free, unit, null, 0, gst, null);
  }

  private static PurchaseMath.DocIn doc(GstMode mode) { return new PurchaseMath.DocIn(mode, false, null, 0, 0, 0, false); }

  @Test
  void aRegularDealerClaimsTheGstBackSoItIsNotInTheCost() {
    var out = PurchaseMath.compute(List.of(line(10, 0, 10_000, 18)), doc(GstMode.REGULAR));
    var l = out.lines().getFirst();
    assertThat(l.taxablePaise()).isEqualTo(100_000);
    assertThat(l.cgstPaise()).isEqualTo(9_000);
    assertThat(l.sgstPaise()).isEqualTo(9_000);
    assertThat(l.lineTotalPaise()).isEqualTo(118_000);
    assertThat(l.landedUnitCost()).isEqualByComparingTo("10000");
    assertThat(out.totalPaise()).isEqualTo(118_000);
    assertThat(out.cgstPaise()).isEqualTo(9_000);
  }

  @Test
  void aCompositionDealerPutsAllTheGstInTheCost() {
    var out = PurchaseMath.compute(List.of(new PurchaseMath.LineIn(10, 0, 10_000, null, 0, 18, BigDecimal.valueOf(12))), doc(GstMode.COMPOSITION));
    var l = out.lines().getFirst();
    assertThat(l.cessPaise()).isEqualTo(12_000);
    assertThat(l.landedTotalPaise()).isEqualTo(100_000 + 18_000 + 12_000);
    assertThat(l.landedUnitCost()).isEqualByComparingTo("13000");
  }

  @Test
  void noGstModeIgnoresTheRatesAndTheCess() {
    var out = PurchaseMath.compute(List.of(new PurchaseMath.LineIn(2, 0, 5_000, null, 0, 28, BigDecimal.TEN)),
        new PurchaseMath.DocIn(GstMode.NONE, false, null, 0, 1_000, 18, false));
    assertThat(out.lines().getFirst().lineTotalPaise()).isEqualTo(10_000);
    assertThat(out.freightGstPaise()).isZero();
    assertThat(out.totalPaise()).isEqualTo(11_000);
  }

  @Test
  void freeUnitsShareTheLineCostAndKeepFourDecimals() {
    var out = PurchaseMath.compute(List.of(line(10, 2, 10_000, 0)), doc(GstMode.REGULAR));
    assertThat(out.lines().getFirst().landedUnitCost()).isEqualByComparingTo("8333.3333");
    assertThat(out.lines().getFirst().landedUnitCost().scale()).isEqualTo(4);
    assertThat(out.totalPaise()).isEqualTo(100_000);
  }

  @Test
  void percentAndAmountDiscountsComeOffBeforeTax() {
    var pct = new PurchaseMath.LineIn(1, 0, 1_000, new BigDecimal("12.5"), 0, 0, null);
    assertThat(PurchaseMath.compute(List.of(pct), doc(GstMode.NONE)).lines().getFirst().lineDiscountPaise()).isEqualTo(125);
    var amount = new PurchaseMath.LineIn(10, 0, 10_000, null, 10_000, 18, null);
    var out = PurchaseMath.compute(List.of(amount), doc(GstMode.REGULAR));
    assertThat(out.lines().getFirst().taxablePaise()).isEqualTo(90_000);
    assertThat(out.lines().getFirst().lineTotalPaise()).isEqualTo(90_000 + 16_200);
    assertThat(out.lineDiscountPaise()).isEqualTo(10_000);
  }

  @Test
  void theDocumentDiscountIsSpreadOverTheLinesAndAddsUpExactly() {
    var out = PurchaseMath.compute(List.of(line(1, 0, 60_000, 0), line(1, 0, 40_000, 0)),
        new PurchaseMath.DocIn(GstMode.NONE, false, null, 1_001, 0, 0, false));
    assertThat(out.lines()).extracting(PurchaseMath.LineOut::docDiscountSharePaise).containsExactly(601L, 400L);
    assertThat(out.docDiscountPaise()).isEqualTo(1_001);
    assertThat(out.taxablePaise()).isEqualTo(100_000 - 1_001);
    var byPercent = PurchaseMath.compute(List.of(line(1, 0, 60_000, 0)), new PurchaseMath.DocIn(GstMode.NONE, false, BigDecimal.TEN, 0, 0, 0, false));
    assertThat(byPercent.docDiscountPaise()).isEqualTo(6_000);
  }

  @Test
  void freightAndItsGstAreAddedAndSplitByValueIntoTheLandedCost() {
    var lines = List.of(line(1, 0, 60_000, 0), line(1, 0, 40_000, 0));
    var regular = PurchaseMath.compute(lines, new PurchaseMath.DocIn(GstMode.REGULAR, false, null, 0, 5_000, 18, false));
    assertThat(regular.freightGstPaise()).isEqualTo(900);
    assertThat(regular.totalPaise()).isEqualTo(105_900);
    assertThat(regular.cgstPaise()).isEqualTo(450);
    assertThat(regular.sgstPaise()).isEqualTo(450);
    assertThat(regular.lines().get(0).landedTotalPaise()).isEqualTo(63_000);
    assertThat(regular.lines().get(1).landedTotalPaise()).isEqualTo(42_000);
    var composition = PurchaseMath.compute(lines, new PurchaseMath.DocIn(GstMode.COMPOSITION, false, null, 0, 5_000, 18, false));
    assertThat(composition.lines().get(0).landedTotalPaise() + composition.lines().get(1).landedTotalPaise()).isEqualTo(105_900);
  }

  @Test
  void freightOnFreeGoodsIsSplitByQuantity() {
    var out = PurchaseMath.compute(List.of(line(3, 0, 0, 0), line(1, 0, 0, 0)), new PurchaseMath.DocIn(GstMode.NONE, false, null, 0, 400, 0, false));
    assertThat(out.lines()).extracting(PurchaseMath.LineOut::landedTotalPaise).containsExactly(300L, 100L);
  }

  @Test
  void interStateTaxIsAllIgstAndAnOddPaisaGoesToSgst() {
    var inter = PurchaseMath.compute(List.of(line(1, 0, 10_100, 5)), new PurchaseMath.DocIn(GstMode.REGULAR, true, null, 0, 1_000, 18, false));
    assertThat(inter.igstPaise()).isEqualTo(505 + 180);
    assertThat(inter.cgstPaise() + inter.sgstPaise()).isZero();
    var intra = PurchaseMath.compute(List.of(line(1, 0, 10_100, 5)), doc(GstMode.REGULAR));
    assertThat(intra.cgstPaise()).isEqualTo(252);
    assertThat(intra.sgstPaise()).isEqualTo(253);
  }

  @Test
  void roundOffBringsTheTotalToAWholeRupee() {
    var up = PurchaseMath.compute(List.of(line(1, 0, 118_050, 0)), new PurchaseMath.DocIn(GstMode.NONE, false, null, 0, 0, 0, true));
    assertThat(up.roundOffPaise()).isEqualTo(50);
    assertThat(up.totalPaise()).isEqualTo(118_100);
    var down = PurchaseMath.compute(List.of(line(1, 0, 118_049, 0)), new PurchaseMath.DocIn(GstMode.NONE, false, null, 0, 0, 0, true));
    assertThat(down.roundOffPaise()).isEqualTo(-49);
    assertThat(down.totalPaise()).isEqualTo(118_000);
    var off = PurchaseMath.compute(List.of(line(1, 0, 118_049, 0)), doc(GstMode.NONE));
    assertThat(off.roundOffPaise()).isZero();
  }

  @Test
  void discountsLargerThanTheirBaseAreRefused() {
    var big = new PurchaseMath.LineIn(1, 0, 1_000, null, 1_001, 0, null);
    assertThatThrownBy(() -> PurchaseMath.compute(List.of(big), doc(GstMode.NONE))).isInstanceOf(ApiException.class).hasMessageContaining("line amount");
    assertThatThrownBy(() -> PurchaseMath.compute(List.of(line(1, 0, 1_000, 0)), new PurchaseMath.DocIn(GstMode.NONE, false, null, 1_001, 0, 0, false)))
        .isInstanceOf(ApiException.class).hasMessageContaining("goods value");
  }

  @Test
  void splitAndPercentHelpers() {
    assertThat(PurchaseMath.split(5, false)).containsExactly(2, 3, 0);
    assertThat(PurchaseMath.split(5, true)).containsExactly(0, 0, 5);
    assertThat(PurchaseMath.percentOf(1_005, new BigDecimal("0.5"))).isEqualTo(5);
  }

  @Test
  void roundingModesAndParsing() {
    var v = new BigDecimal("8333.3333");
    assertThat(Rounding.EXACT.apply(v)).isEqualByComparingTo("8333.3333");
    assertThat(Rounding.PAISE.apply(v)).isEqualByComparingTo("8333");
    assertThat(Rounding.RUPEE.apply(v)).isEqualByComparingTo("8300");
    assertThat(Rounding.RUPEE.apply(new BigDecimal("8350"))).isEqualByComparingTo("8400");
    assertThat(Rounding.parse(null)).isEqualTo(Rounding.PAISE);
    assertThat(Rounding.parse(" ")).isEqualTo(Rounding.PAISE);
    assertThat(Rounding.parse("rupee")).isEqualTo(Rounding.RUPEE);
    assertThatThrownBy(() -> Rounding.parse("up")).isInstanceOf(ApiException.class);
    assertThat(GstMode.parse(null, GstMode.NONE)).isEqualTo(GstMode.NONE);
    assertThat(GstMode.parse(" ", GstMode.REGULAR)).isEqualTo(GstMode.REGULAR);
    assertThat(GstMode.parse("composition", GstMode.REGULAR)).isEqualTo(GstMode.COMPOSITION);
    assertThat(GstMode.REGULAR.creditable()).isTrue();
    assertThat(GstMode.COMPOSITION.creditable()).isFalse();
    assertThatThrownBy(() -> GstMode.parse("x", GstMode.NONE)).isInstanceOf(ApiException.class);
  }
}
