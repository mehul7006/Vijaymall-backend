package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BillCalculatorTest {
  private static BillCalculator.Draft unit(String n, int qty, long price, int gst) {
    return new BillCalculator.Draft(UUID.randomUUID(), n, SoldBy.UNIT, qty, price, gst, "1006");
  }
  private static BillCalculator.Draft kg(String n, int grams, long perKg, int gst) {
    return new BillCalculator.Draft(UUID.randomUUID(), n, SoldBy.WEIGHT, grams, perKg, gst, "0702");
  }

  @Test void noDiscount_totalsAndTax() {
    var b = BillCalculator.build(List.of(unit("Rice", 2, 5900, 5), unit("Soap", 1, 11800, 18)), 0);
    assertThat(b.subtotalPaise()).isEqualTo(23600);
    assertThat(b.totalPaise()).isEqualTo(23600);
    assertThat(b.discountPaise()).isZero();
    assertThat(b.tax().gross()).isEqualTo(23600);
    assertThat(b.tax().taxable() + b.tax().cgst() + b.tax().sgst()).isEqualTo(23600);
    assertThat(b.lines().get(1).tax()).isEqualTo(new TaxSplit(11800, 10000, 900, 900));
  }
  @Test void weighedLine_usesPricePerKg() {
    var b = BillCalculator.build(List.of(kg("Tomato", 1250, 4000, 0)), 0);
    assertThat(b.lines().get(0).linePaise()).isEqualTo(5000);
  }
  @Test void discount_isSpreadProportionally_andTotalsMatch() {
    var b = BillCalculator.build(List.of(unit("A", 1, 6000, 5), unit("B", 1, 4000, 12)), 1000);
    assertThat(b.lines().get(0).discountPaise()).isEqualTo(600);
    assertThat(b.lines().get(1).discountPaise()).isEqualTo(400);
    assertThat(b.totalPaise()).isEqualTo(9000);
    assertThat(b.lines().get(0).netPaise() + b.lines().get(1).netPaise()).isEqualTo(9000);
    assertThat(b.tax().gross()).isEqualTo(9000);
  }
  @Test void discountAboveSubtotal_isCapped() {
    var b = BillCalculator.build(List.of(unit("A", 1, 500, 0)), 99999);
    assertThat(b.discountPaise()).isEqualTo(500);
    assertThat(b.totalPaise()).isZero();
    assertThat(b.tax()).isEqualTo(TaxSplit.ZERO);
  }
  @Test void negativeDiscount_treatedAsZero() {
    assertThat(BillCalculator.build(List.of(unit("A", 1, 500, 0)), -5).totalPaise()).isEqualTo(500);
  }
  @Test void emptyCart_isZeroBill() {
    var b = BillCalculator.build(List.of(), 0);
    assertThat(b.totalPaise()).isZero();
    assertThat(b.lines()).isEmpty();
  }
}
