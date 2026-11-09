package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The counter display name: name, variant and pack size without repeated words. */
class DisplayNamesTest {
  @Test void joinsNameVariantAndPackSize() {
    assertThat(DisplayNames.of("Basmati Rice", "Premium", "1 kg")).isEqualTo("Basmati Rice Premium 1 kg");
  }

  @Test void doesNotRepeatWordsAlreadyInTheName() {
    assertThat(DisplayNames.of("Mango Juice", "mango", "1 L")).isEqualTo("Mango Juice 1 L");
    assertThat(DisplayNames.of("Mango Juice", "Mango Large", "")).isEqualTo("Mango Juice Large");
    assertThat(DisplayNames.of("Basmati Rice 1 kg", "", "1 KG")).isEqualTo("Basmati Rice 1 kg");
  }

  @Test void missingPartsAreSkipped() {
    assertThat(DisplayNames.of("Soap", null, null)).isEqualTo("Soap");
    assertThat(DisplayNames.of("Soap", "  ", "  ")).isEqualTo("Soap");
    assertThat(DisplayNames.of("Soap", null, "100 g")).isEqualTo("Soap 100 g");
    assertThat(DisplayNames.of(null, "Red", "1 pc")).isEqualTo("Red 1 pc");
  }
}
