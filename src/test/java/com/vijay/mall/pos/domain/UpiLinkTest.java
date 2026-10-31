package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UpiLinkTest {
  @Test void builds_link_withEncodedParts() {
    assertThat(UpiLink.build("shop@upi", "Vijay Super Mall", 123450, "VSM/2026-27/000001"))
        .isEqualTo("upi://pay?pa=shop%40upi&pn=Vijay%20Super%20Mall&am=1234.50&cu=INR&tn=VSM%2F2026-27%2F000001");
  }
  @Test void nullPayeeAndNote_becomeEmpty() {
    assertThat(UpiLink.build("a@b", null, 100, null)).isEqualTo("upi://pay?pa=a%40b&pn=&am=1.00&cu=INR&tn=");
  }
  @Test void missingUpiId_throws() {
    assertThatThrownBy(() -> UpiLink.build(" ", "x", 1, "y")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> UpiLink.build(null, "x", 1, "y")).isInstanceOf(IllegalArgumentException.class);
  }
}
