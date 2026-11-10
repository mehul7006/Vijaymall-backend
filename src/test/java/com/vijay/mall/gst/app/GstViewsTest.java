package com.vijay.mall.gst.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GstViewsTest {
  @Test void ratesLoseTrailingZerosButNeverTurnIntoExponents() {
    assertThat(GstViews.clean(new BigDecimal("5.000")).toPlainString()).isEqualTo("5");
    assertThat(GstViews.clean(new BigDecimal("2.500")).toPlainString()).isEqualTo("2.5");
    assertThat(GstViews.clean(new BigDecimal("0.000")).toPlainString()).isEqualTo("0");
    assertThat(GstViews.clean(new BigDecimal("0.250")).toPlainString()).isEqualTo("0.25");
    assertThat(GstViews.clean(new BigDecimal("100")).toString()).isEqualTo("100");
    assertThat(GstViews.clean(new BigDecimal("1E+2")).toString()).isEqualTo("100");
    assertThat(GstViews.clean(null)).isNull();
  }
}
