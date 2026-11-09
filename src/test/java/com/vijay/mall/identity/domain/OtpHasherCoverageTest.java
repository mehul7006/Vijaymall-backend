package com.vijay.mall.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OtpHasherCoverageTest {
  @Test
  void hmacWithoutAPepperFailsAsIllegalState() {
    assertThatThrownBy(() -> OtpHasher.hmacHex(null, "msg")).isInstanceOf(IllegalStateException.class);
    // an empty key is refused by the HMAC implementation as well
    assertThatThrownBy(() -> OtpHasher.hmacHex("", "msg")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sha256WithoutAValueFailsAsIllegalState() {
    assertThatThrownBy(() -> OtpHasher.sha256Hex(null)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sha256OfEmptyStringIsTheKnownDigest() {
    assertThat(OtpHasher.sha256Hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }
}
