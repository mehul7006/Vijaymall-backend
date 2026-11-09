package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GtinTest {
  @Test void checkDigit_knownCodes() {
    assertThat(Gtin.checkDigit("400638133393")).isEqualTo(1);
    assertThat(Gtin.checkDigit("890100000001")).isEqualTo(BarcodeParser.checkDigit("890100000001") - '0');
    assertThat(Gtin.checkDigit("9638507")).isEqualTo(4);
    assertThat(Gtin.checkDigit("03600029145")).isEqualTo(2);
    assertThat(Gtin.withCheckDigit("400638133393")).isEqualTo("4006381333931");
  }

  @ParameterizedTest
  @ValueSource(strings = {"4006381333931", "96385074", "036000291452", "5901234123457"})
  void valid_realCodes(String code) { assertThat(Gtin.isValid(code)).isTrue(); }

  @ParameterizedTest
  @ValueSource(strings = {"4006381333932", "96385075", "036000291453", "abc", "", "12345", "12345678901234"})
  void invalid_wrongDigitOrWrongLength(String code) { assertThat(Gtin.isValid(code)).isFalse(); }

  @Test void invalid_null() { assertThat(Gtin.isValid(null)).isFalse(); assertThat(Gtin.isStandardLength(null)).isFalse(); }

  @Test void standardLength_is8_12_13_digits() {
    assertThat(Gtin.isStandardLength("12345678")).isTrue();
    assertThat(Gtin.isStandardLength("123456789012")).isTrue();
    assertThat(Gtin.isStandardLength("1234567890123")).isTrue();
    assertThat(Gtin.isStandardLength("1234567")).isFalse();
    assertThat(Gtin.isStandardLength("ABCDEFGH")).isFalse();
  }

  @Test void problem_onlyForStandardLengthsWithAWrongDigit() {
    assertThat(Gtin.checkDigitProblem("4006381333932")).hasValueSatisfying(m -> assertThat(m).contains("should be 1"));
    assertThat(Gtin.checkDigitProblem("4006381333931")).isEmpty();
    assertThat(Gtin.checkDigitProblem("ZZ-BULK-1")).isEmpty();
    assertThat(Gtin.checkDigitProblem("12345")).isEmpty();
  }

  @Test void matchesTheParserHelpers() {
    for (String body : new String[] {"200010101500", "890100000001", "29000000000"}) {
      assertThat((char) ('0' + Gtin.checkDigit(body))).isEqualTo(BarcodeParser.checkDigit(body));
    }
  }
}
