package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PosViewsBillViewTest {
  @Test
  void aBillWithoutRoundingHasNoRoundOff() {
    var v = new PosViews.BillView(List.of(), 1000, "SAVE", 100, 5, 50, 850, 800, 25, 25, 8, 120, List.of("w"));
    assertThat(v.roundOffPaise()).isZero();
    assertThat(v.totalPaise()).isEqualTo(850);
    assertThat(v.warnings()).containsExactly("w");
    assertThat(v.couponCode()).isEqualTo("SAVE");
  }
}
