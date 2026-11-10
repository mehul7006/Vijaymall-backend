package com.vijay.mall.gst.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BillingRateRuleTest {
  @ParameterizedTest
  @CsvSource({"0,true", "0.000,true", "3,true", "5.000,true", "12,true", "18,true", "28,true", "40,true", "1.5,true", "0.25,true", "0.250,true",
      "7,false", "100,false", "1E+3,false", "-5,false"})
  void onlyTheRatesOfTheGstRateListAreAccepted(String rate, boolean accepted) {
    assertThat(BillingRateRule.accepts(new BigDecimal(rate))).isEqualTo(accepted);
  }

  @Test
  void nullIsNotAccepted() {
    assertThat(BillingRateRule.accepts(null)).isFalse();
  }
}
