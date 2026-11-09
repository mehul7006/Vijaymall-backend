package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Payment modes are trimmed and upper-cased in one shared place, and bad ones quote what was received. */
class PaymentModeNormalisingTest {
  @Test void normalizeTrimsAndUpperCases_andKeepsNull() {
    assertThat(PaymentRules.normalizeMode("  cash ")).isEqualTo("CASH");
    assertThat(PaymentRules.normalizeMode("Upi")).isEqualTo("UPI");
    assertThat(PaymentRules.normalizeMode(null)).isNull();
  }

  @Test void requireModeAcceptsTheThreeModes_andCreditOnlyOnABill() {
    assertThat(PaymentRules.requireMode(" card", false)).isEqualTo("CARD");
    assertThat(PaymentRules.requireMode("credit ", true)).isEqualTo("CREDIT");
    assertThatThrownBy(() -> PaymentRules.requireMode("credit", false))
        .hasMessage("payment mode must be CASH, UPI or CARD (got \"credit\")");
  }

  @Test void badModeMessageQuotesTheValueReceived() {
    assertThatThrownBy(() -> PaymentRules.requireMode("xyz", false))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("payment mode must be CASH, UPI or CARD (got \"xyz\")");
    assertThatThrownBy(() -> PaymentRules.requireMode("xyz", true))
        .hasMessage("payment mode must be CASH, UPI or CARD, or CREDIT on a bill (got \"xyz\")");
  }

  @Test void aMissingModeIsReportedAsNone_notAnNpe() {
    assertThatThrownBy(() -> PaymentRules.requireMode(null, false)).hasMessage("payment mode must be CASH, UPI or CARD (got none)");
    assertThatThrownBy(() -> PaymentRules.change(List.of(new PaymentRules.Line(null, 100, null)), 100, null))
        .hasMessageContaining("(got none)");
  }
}
