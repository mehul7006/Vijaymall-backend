package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class SoldByTest {
  @Test void unit_multiplies() { assertThat(SoldBy.UNIT.lineAmount(2500, 3)).isEqualTo(7500); }
  @Test void weight_pricePerKg() { assertThat(SoldBy.WEIGHT.lineAmount(8000, 500)).isEqualTo(4000); }
  @Test void weight_roundsHalfUp() {
    assertThat(SoldBy.WEIGHT.lineAmount(9900, 125)).isEqualTo(1238); // 1237.5
    assertThat(SoldBy.WEIGHT.lineAmount(9900, 124)).isEqualTo(1228); // 1227.6
    assertThat(SoldBy.WEIGHT.lineAmount(1, 499)).isEqualTo(0);
    assertThat(SoldBy.WEIGHT.lineAmount(1, 500)).isEqualTo(1);
  }
  @Test void weight_zeroQty() { assertThat(SoldBy.WEIGHT.lineAmount(8000, 0)).isZero(); }
  @Test void qtyForAmount_weight_gramsFromPrice() {
    assertThat(SoldBy.WEIGHT.qtyForAmount(8000, 4000)).isEqualTo(500);
    assertThat(SoldBy.WEIGHT.qtyForAmount(9900, 1238)).isEqualTo(125);
  }
  @Test void qtyForAmount_unit_wholePieces() { assertThat(SoldBy.UNIT.qtyForAmount(2500, 7600)).isEqualTo(3); }
  @Test void qtyForAmount_badPrice_throws() {
    Assertions.assertThatThrownBy(() -> SoldBy.WEIGHT.qtyForAmount(0, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
