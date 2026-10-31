package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentRulesTest {
  private static PaymentRules.Line cash(long a) { return new PaymentRules.Line("CASH", a, null); }
  private static PaymentRules.Line upi(long a) { return new PaymentRules.Line("UPI", a, "ref1"); }

  @Test void exactSingleCash_noChange() { assertThat(PaymentRules.change(List.of(cash(5000)), 5000, null)).isZero(); }
  @Test void splitPayment_adds() { assertThat(PaymentRules.change(List.of(cash(2000), upi(3000)), 5000, null)).isZero(); }
  @Test void tendered_givesChange() { assertThat(PaymentRules.change(List.of(cash(2000), upi(3000)), 5000, 2500L)).isEqualTo(500); }
  @Test void tenderedEqualsCash_zeroChange() { assertThat(PaymentRules.change(List.of(cash(5000)), 5000, 5000L)).isZero(); }
  @Test void zeroBill_noPayments_ok() { assertThat(PaymentRules.change(List.of(), 0, null)).isZero(); }
  @Test void zeroBill_withPayment_rejected() {
    assertThatThrownBy(() -> PaymentRules.change(List.of(cash(100)), 0, null)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nothing to pay");
  }
  @Test void sumMismatch_rejected() {
    assertThatThrownBy(() -> PaymentRules.change(List.of(cash(4000)), 5000, null)).hasMessageContaining("add up");
    assertThatThrownBy(() -> PaymentRules.change(List.of(), 5000, null)).hasMessageContaining("add up");
  }
  @Test void badMode_rejected() {
    assertThatThrownBy(() -> PaymentRules.change(List.of(new PaymentRules.Line("CHEQUE", 5000, null)), 5000, null))
        .hasMessageContaining("CASH, UPI or CARD");
  }
  @Test void nonPositiveAmount_rejected() {
    assertThatThrownBy(() -> PaymentRules.change(List.of(cash(0), cash(5000)), 5000, null)).hasMessageContaining("positive");
  }
  @Test void tenderedWithoutCash_rejected() {
    assertThatThrownBy(() -> PaymentRules.change(List.of(upi(5000)), 5000, 6000L)).hasMessageContaining("no cash");
  }
  @Test void tenderedBelowCash_rejected() {
    assertThatThrownBy(() -> PaymentRules.change(List.of(cash(5000)), 5000, 4000L)).hasMessageContaining("less than");
  }
  @Test void refund_mustMatch() {
    PaymentRules.checkRefund(List.of(cash(300), upi(200)), 500);
    assertThatThrownBy(() -> PaymentRules.checkRefund(List.of(cash(300)), 500)).hasMessageContaining("refunds add up");
  }
}
