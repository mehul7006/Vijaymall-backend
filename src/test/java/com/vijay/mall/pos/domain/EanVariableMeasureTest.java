package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EanVariableMeasureTest {
  private final VariableMeasureBarcode on = new EanVariableMeasure(true, "22");
  private final VariableMeasureBarcode off = new EanVariableMeasure(false, "22");

  @Test void generate_prefixItemValueAndCheckDigit() {
    String code = on.generate("123", 1500);
    assertThat(code).hasSize(13).startsWith("22" + "00123" + "01500");
    assertThat(Gtin.isValid(code)).isTrue();
    assertThat(on.generate("99999", 99999)).startsWith("229999999999");
    assertThat(on.generate("1", 0)).startsWith("220000100000");
  }

  @Test void parse_roundTrips() {
    var reading = on.parse(on.generate("123", 1500));
    assertThat(reading).hasValue(new VariableMeasureBarcode.Reading("00123", 1500));
  }

  @Test void parse_rejectsOtherPrefixBadCheckDigitBadShape() {
    assertThat(on.parse(Gtin.withCheckDigit("230012301500"))).isEmpty();
    assertThat(on.parse("2200123015009")).isEmpty();
    assertThat(on.parse("22001230150")).isEmpty();
    assertThat(on.parse(null)).isEmpty();
  }

  @Test void disabled_doesNothing() {
    assertThat(off.enabled()).isFalse();
    assertThat(on.enabled()).isTrue();
    assertThat(off.parse("2200123015001")).isEmpty();
    assertThatThrownBy(() -> off.generate("1", 1)).isInstanceOf(IllegalStateException.class);
  }

  @Test void generate_rejectsBadInput() {
    assertThatThrownBy(() -> on.generate("123456", 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> on.generate(null, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> on.generate("1", -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> on.generate("1", 100000)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void prefix_mustBeTwoDigitsInTheTwenties() {
    assertThatThrownBy(() -> new EanVariableMeasure(true, "3")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EanVariableMeasure(true, "2")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EanVariableMeasure(true, null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new EanVariableMeasure(true, "29").enabled()).isTrue();
  }
}
