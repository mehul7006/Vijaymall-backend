package com.vijay.mall.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.identity.domain.MobileNumbers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MobileNumbersTest {
  @ParameterizedTest
  @ValueSource(strings = {"9876543210", " 98765 43210 ", "+91 98765-43210", "+919876543210", "919876543210",
      "09876543210", "0091 9876543210", "(98765) 43210"})
  void commonWaysOfWritingANumberGiveTheSameTenDigits(String raw) {
    assertThat(MobileNumbers.normalize(raw)).contains("9876543210");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "12345", "5876543210", "98765432101", "98765abcde", "+1 9876543210", "+91 12345 67890"})
  void anythingElseIsRejected(String raw) {
    assertThat(MobileNumbers.normalize(raw)).isEmpty();
  }

  @Test
  void numbersMustStartWithSixToNine() {
    assertThat(MobileNumbers.normalize("6000000000")).isPresent();
    assertThat(MobileNumbers.normalize("9999999999")).isPresent();
    assertThat(MobileNumbers.normalize("5999999999")).isEmpty();
  }
}
