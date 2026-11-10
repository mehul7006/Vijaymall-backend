package com.vijay.mall.common.tax;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GstRatesTest {
  private final JsonMapper json = JsonMapper.builder().build();

  record Out(@GstRateJson BigDecimal gstPercent) { }

  @Test void allNineCurrentRatesAreAcceptedWhateverTheirScale() {
    for (String ok : new String[] {"0", "0.0", "0.00", "0.25", "0.250", "1.5", "1.50", "3", "5.0", "12", "18.00", "28", "40", "40.0"}) {
      assertThat(GstRates.accepts(new BigDecimal(ok))).as(ok).isTrue();
    }
    assertThat(GstRates.ALLOWED).hasSize(9);
  }

  @Test void otherRatesAreRefusedWithTheFixedText() {
    for (String bad : new String[] {"-1", "0.5", "1", "2.5", "7", "18.01", "17.99", "100", "400"}) {
      assertThat(GstRates.accepts(new BigDecimal(bad))).as(bad).isFalse();
    }
    assertThat(GstRates.accepts(null)).isFalse();
    assertThatThrownBy(() -> GstRates.require(new BigDecimal("7"), "gstPercent")).isInstanceOf(ApiException.class)
        .hasMessageContaining("must be one of 0, 0.25, 1.5, 3, 5, 12, 18, 28 or 40");
    assertThatThrownBy(() -> GstRates.require(null, "gstPercent")).isInstanceOf(ApiException.class);
  }

  @Test void requireReturnsTheNormalisedRate() {
    assertThat(GstRates.require(new BigDecimal("18.00"), "x")).isEqualTo(new BigDecimal("18"));
    assertThat(GstRates.require(new BigDecimal("1.50"), "x")).isEqualTo(new BigDecimal("1.5"));
    assertThat(GstRates.normalize(new BigDecimal("4E+1"))).isEqualTo(new BigDecimal("40"));
    assertThat(GstRates.normalize(new BigDecimal("0.00"))).isEqualTo(BigDecimal.ZERO);
    assertThat(GstRates.normalize(null)).isNull();
  }

  @Test void textAndHundredths() {
    assertThat(GstRates.plain(new BigDecimal("18.00"))).isEqualTo("18");
    assertThat(GstRates.plain(new BigDecimal("0.250"))).isEqualTo("0.25");
    assertThat(GstRates.plain(new BigDecimal("4E+1"))).isEqualTo("40");
    assertThat(GstRates.thousandths(new BigDecimal("1.5"))).isEqualTo(1500);
    assertThat(GstRates.thousandths(new BigDecimal("40"))).isEqualTo(40000);
    assertThat(GstRates.of("1.50")).isEqualTo(new BigDecimal("1.5"));
    assertThat(GstRates.of(28)).isEqualTo(new BigDecimal("28"));
  }

  @Test void ratesAreWrittenAsNumbersWithoutNeedlessZeros() {
    assertThat(json.writeValueAsString(new Out(new BigDecimal("18.00")))).isEqualTo("{\"gstPercent\":18}");
    assertThat(json.writeValueAsString(new Out(new BigDecimal("5.0")))).isEqualTo("{\"gstPercent\":5}");
    assertThat(json.writeValueAsString(new Out(new BigDecimal("0.25")))).isEqualTo("{\"gstPercent\":0.25}");
    assertThat(json.writeValueAsString(new Out(new BigDecimal("1.50")))).isEqualTo("{\"gstPercent\":1.5}");
    assertThat(json.writeValueAsString(new Out(new BigDecimal("0.00")))).isEqualTo("{\"gstPercent\":0}");
    assertThat(json.writeValueAsString(new Out(new BigDecimal("1E+2")))).isEqualTo("{\"gstPercent\":100}");
    assertThat(json.writeValueAsString(new Out(null))).isEqualTo("{\"gstPercent\":null}");
  }

  @Test void jsonNumbersOfAnyShapeAreReadAndCompareNumerically() {
    for (String text : new String[] {"18", "18.0", "18.00", "1.8E+1"}) {
      assertThat(GstRates.accepts(json.readValue("{\"gstPercent\":" + text + "}", Out.class).gstPercent())).as(text).isTrue();
    }
    assertThat(GstRates.accepts(json.readValue("{\"gstPercent\":1.5}", Out.class).gstPercent())).isTrue();
    assertThat(GstRates.accepts(json.readValue("{\"gstPercent\":0.25}", Out.class).gstPercent())).isTrue();
    assertThat(GstRates.accepts(json.readValue("{\"gstPercent\":18.5}", Out.class).gstPercent())).isFalse();
  }
}
