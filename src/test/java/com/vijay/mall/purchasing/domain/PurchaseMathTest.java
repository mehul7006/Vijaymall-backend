package com.vijay.mall.purchasing.domain;

import com.vijay.mall.common.tax.GstRates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PurchaseMathTest {
  private static PurchaseMath.LineIn line(int qty, int free, long unit, int gst) {
    return new PurchaseMath.LineIn(qty, free, BigDecimal.valueOf(unit), null, 0, GstRates.of(gst), null);
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
    var out = PurchaseMath.compute(List.of(new PurchaseMath.LineIn(10, 0, BigDecimal.valueOf(10_000), null, 0, GstRates.of(18), BigDecimal.valueOf(12))), doc(GstMode.COMPOSITION));
    var l = out.lines().getFirst();
    assertThat(l.cessPaise()).isEqualTo(12_000);
    assertThat(l.landedTotalPaise()).isEqualTo(100_000 + 18_000 + 12_000);
    assertThat(l.landedUnitCost()).isEqualByComparingTo("13000");
  }

  @Test
  void noGstModeIgnoresTheRatesAndTheCess() {
    var out = PurchaseMath.compute(List.of(new PurchaseMath.LineIn(2, 0, BigDecimal.valueOf(5_000), null, 0, GstRates.of(28), BigDecimal.TEN)),
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
    var pct = new PurchaseMath.LineIn(1, 0, BigDecimal.valueOf(1_000), new BigDecimal("12.5"), 0, GstRates.of(0), null);
    assertThat(PurchaseMath.compute(List.of(pct), doc(GstMode.NONE)).lines().getFirst().lineDiscountPaise()).isEqualTo(125);
    var amount = new PurchaseMath.LineIn(10, 0, BigDecimal.valueOf(10_000), null, 10_000, GstRates.of(18), null);
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
    var big = new PurchaseMath.LineIn(1, 0, BigDecimal.valueOf(1_000), null, 1_001, GstRates.of(0), null);
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

  @Test
  void aWeighedLineAtFiftyFiveRupeesAKiloIsTwelveThousandGramsTimesFivePointFivePaise() {
    var l = new PurchaseMath.LineIn(12_000, 0, new BigDecimal("5.5"), null, 0, GstRates.of(0), null);
    var out = PurchaseMath.compute(List.of(l), doc(GstMode.NONE));
    assertThat(out.lines().getFirst().grossPaise()).isEqualTo(66_000);
    assertThat(out.lines().getFirst().taxablePaise()).isEqualTo(66_000);
    assertThat(out.lines().getFirst().landedUnitCost()).isEqualByComparingTo("5.5");
    assertThat(out.totalPaise()).isEqualTo(66_000);
  }

  @Test
  void aFractionalLineIsRoundedHalfUpToWholePaiseOncePerLineBeforeDiscountsAndTax() {
    assertThat(PurchaseMath.gross(1, new BigDecimal("2.5"))).isEqualTo(3);
    assertThat(PurchaseMath.gross(3, new BigDecimal("0.1666"))).isEqualTo(0);
    assertThat(PurchaseMath.gross(3, new BigDecimal("0.1667"))).isEqualTo(1);
    var l = new PurchaseMath.LineIn(3, 0, new BigDecimal("33.3333"), new BigDecimal("10"), 0, GstRates.of(18), null);
    var out = PurchaseMath.compute(List.of(l), doc(GstMode.REGULAR)).lines().getFirst();
    assertThat(out.grossPaise()).isEqualTo(100);
    assertThat(out.lineDiscountPaise()).isEqualTo(10);
    assertThat(out.taxablePaise()).isEqualTo(90);
  }

  @Test
  void wholeNumberUnitCostsGiveExactlyQuantityTimesCostAsBefore() {
    for (int qty : new int[] {1, 7, 1000, 999_999}) {
      for (long unit : new long[] {0, 1, 99, 10_000, 100_000_000}) {
        long expected = qty * unit;
        assertThat(PurchaseMath.gross(qty, BigDecimal.valueOf(unit))).isEqualTo(expected);
        assertThat(PurchaseMath.gross(qty, new BigDecimal(unit + ".0000"))).isEqualTo(expected);
        assertThat(PurchaseMath.gross(qty, BigDecimal.valueOf(unit).stripTrailingZeros())).isEqualTo(expected);
        var out = PurchaseMath.compute(List.of(line(qty, 0, unit, 12)), doc(GstMode.REGULAR)).lines().getFirst();
        assertThat(out.grossPaise()).isEqualTo(expected);
        assertThat(out.taxablePaise()).isEqualTo(expected);
      }
    }
  }

  @Test
  void plainPrintsACostWithoutNeedlessZerosOrAnExponent() {
    assertThat(PurchaseMath.plain(new BigDecimal("55.0000")).toString()).isEqualTo("55");
    assertThat(PurchaseMath.plain(new BigDecimal("5.5000")).toString()).isEqualTo("5.5");
    assertThat(PurchaseMath.plain(new BigDecimal("100")).toString()).isEqualTo("100");
    assertThat(PurchaseMath.plain(new BigDecimal("0.0000")).toString()).isEqualTo("0");
    assertThat(PurchaseMath.plain(null)).isNull();
  }

  /*
   * Three lines of 10 x 100.00 (taxable 1,00,000 paise each) at 1.5%, 0.25% and 40%: GST 1500, 250 and 40000 paise, halves 750/750,
   * 125/125 and 20000/20000. Freight 1000 paise at 5% is 50 paise (CGST 25, SGST 25). The total is
   * 300000 + 41750 + 1000 + 50 = 342800.
   */
  private static List<PurchaseMath.LineIn> fractionalLines() {
    return List.of(new PurchaseMath.LineIn(10, 0, BigDecimal.valueOf(10_000), null, 0, GstRates.of("1.5"), null),
        new PurchaseMath.LineIn(10, 0, BigDecimal.valueOf(10_000), null, 0, GstRates.of("0.25"), null),
        new PurchaseMath.LineIn(10, 0, BigDecimal.valueOf(10_000), null, 0, GstRates.of("40"), null));
  }

  private static PurchaseMath.DocIn fractionalDoc(GstMode mode, boolean interState) {
    return new PurchaseMath.DocIn(mode, interState, null, 0, 1_000, 5, false);
  }

  @Test
  void regularModeCreditsFractionalRatesAndKeepsTheTaxOutOfTheCost() {
    var out = PurchaseMath.compute(fractionalLines(), fractionalDoc(GstMode.REGULAR, false));
    assertThat(out.lines().stream().map(PurchaseMath.LineOut::cgstPaise)).containsExactly(750L, 125L, 20_000L);
    assertThat(out.lines().stream().map(PurchaseMath.LineOut::sgstPaise)).containsExactly(750L, 125L, 20_000L);
    assertThat(out.freightGstPaise()).isEqualTo(50);
    assertThat(out.cgstPaise()).isEqualTo(20_875 + 25);
    assertThat(out.sgstPaise()).isEqualTo(20_875 + 25);
    assertThat(out.totalPaise()).isEqualTo(342_800);
    assertThat(out.lines().stream().map(PurchaseMath.LineOut::landedTotalPaise)).containsExactly(100_334L, 100_333L, 100_333L);
  }

  @Test
  void compositionModePutsTheFractionalTaxIntoTheCost() {
    var out = PurchaseMath.compute(fractionalLines(), fractionalDoc(GstMode.COMPOSITION, false));
    assertThat(out.lines().stream().map(PurchaseMath.LineOut::landedTotalPaise)).containsExactly(101_850L, 100_250L + 350, 140_350L);
  }

  @Test
  void noGstModeIgnoresFractionalRates() {
    var out = PurchaseMath.compute(fractionalLines(), fractionalDoc(GstMode.NONE, false));
    assertThat(out.cgstPaise()).isZero();
    assertThat(out.freightGstPaise()).isZero();
    assertThat(out.totalPaise()).isEqualTo(301_000);
  }

  @Test
  void interStatePurchasesCarryFractionalRatesAsIgst() {
    var out = PurchaseMath.compute(fractionalLines(), fractionalDoc(GstMode.REGULAR, true));
    assertThat(out.lines().stream().map(PurchaseMath.LineOut::igstPaise)).containsExactly(1_500L, 250L, 40_000L);
    assertThat(out.igstPaise()).isEqualTo(41_800);
    assertThat(out.cgstPaise()).isZero();
  }
}
