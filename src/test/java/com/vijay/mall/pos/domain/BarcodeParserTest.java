package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BarcodeParserTest {
  private static String ean13(String body12) { return body12 + BarcodeParser.checkDigit(body12); }

  @Test void knownEan13_isValid() { assertThat(BarcodeParser.validEan("4006381333931")).isTrue(); }
  @Test void wrongCheckDigit_isInvalid() { assertThat(BarcodeParser.validEan("4006381333932")).isFalse(); }
  @Test void ean8_valid() { assertThat(BarcodeParser.validEan("96385074")).isTrue(); }
  @Test void upcA_valid() { assertThat(BarcodeParser.validEan("036000291452")).isTrue(); }
  @Test void nonNumericOrOddLength_invalid() {
    assertThat(BarcodeParser.validEan("ABC")).isFalse();
    assertThat(BarcodeParser.validEan("12345")).isFalse();
    assertThat(BarcodeParser.validEan(null)).isFalse();
  }
  @Test void checkDigit_computed() { assertThat(BarcodeParser.checkDigit("400638133393")).isEqualTo('1'); }

  @Test void blankOrNull_isEmpty() {
    assertThat(BarcodeParser.parse(null, "2", WeighedMode.WEIGHT_GRAMS)).isEmpty();
    assertThat(BarcodeParser.parse("   ", "2", WeighedMode.WEIGHT_GRAMS)).isEmpty();
  }
  @Test void normalPack_isPlain_andTrimmed() {
    assertThat(BarcodeParser.parse(" 8901234567890 ", "2", WeighedMode.WEIGHT_GRAMS))
        .contains(new ScannedCode.Plain("8901234567890"));
  }
  @Test void shortCodeStartingWithPrefix_isPlain() {
    assertThat(BarcodeParser.parse("2123", "2", WeighedMode.WEIGHT_GRAMS)).contains(new ScannedCode.Plain("2123"));
  }
  @Test void nonDigitCode_isPlain() {
    assertThat(BarcodeParser.parse("2ABCDEFGHIJKL", "2", WeighedMode.WEIGHT_GRAMS))
        .contains(new ScannedCode.Plain("2ABCDEFGHIJKL"));
  }
  @Test void emptyPrefix_neverWeighed() {
    assertThat(BarcodeParser.parse(ean13("212345001500"), "", WeighedMode.WEIGHT_GRAMS)).isPresent()
        .get().isInstanceOf(ScannedCode.Plain.class);
  }
  @Test void weighedLabel_gramsMode() {
    String code = ean13("212345001500"); // PLU 123450, 01500 g
    assertThat(BarcodeParser.parse(code, "2", WeighedMode.WEIGHT_GRAMS))
        .contains(new ScannedCode.Weighed("123450", 1500, WeighedMode.WEIGHT_GRAMS));
  }
  @Test void weighedLabel_priceMode() {
    String code = ean13("200001012345");
    assertThat(BarcodeParser.parse(code, "2", WeighedMode.PRICE_PAISE))
        .contains(new ScannedCode.Weighed("000010", 12345, WeighedMode.PRICE_PAISE));
  }
  @Test void twoDigitPrefix_pluIsFiveDigits() {
    String code = ean13("201234500250");
    assertThat(BarcodeParser.parse(code, "20", WeighedMode.WEIGHT_GRAMS))
        .contains(new ScannedCode.Weighed("12345", 250, WeighedMode.WEIGHT_GRAMS));
  }
  @Test void weighedLabel_badCheckDigit_throws() {
    String good = ean13("212345001500");
    String bad = good.substring(0, 12) + (good.charAt(12) == '0' ? '1' : '0');
    assertThatThrownBy(() -> BarcodeParser.parse(bad, "2", WeighedMode.WEIGHT_GRAMS))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("check digit");
  }
}
