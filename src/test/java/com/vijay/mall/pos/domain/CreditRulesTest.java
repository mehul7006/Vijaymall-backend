package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CreditRulesTest {
  private static PaymentRules.Line line(String mode, long amount) { return new PaymentRules.Line(mode, amount, null); }

  // ----- PaymentRules -----
  @Test
  void aBillMayBePaidPartlyOnCredit() {
    assertThat(PaymentRules.change(List.of(line("CASH", 3000), line("CREDIT", 7000)), 10000, null)).isZero();
    assertThat(PaymentRules.change(List.of(line("CREDIT", 10000)), 10000, null)).isZero();
  }

  @Test
  void changeIsStillGivenOnTheCashShare() {
    assertThat(PaymentRules.change(List.of(line("CASH", 3000), line("CREDIT", 7000)), 10000, 5000L)).isEqualTo(2000);
  }

  @Test
  void creditCannotHaveCashTendered() {
    assertThatThrownBy(() -> PaymentRules.change(List.of(line("CREDIT", 10000)), 10000, 10000L)).hasMessageContaining("no cash");
  }

  @Test
  void onlyOneCreditLineAndTheAmountsMustAddUp() {
    assertThatThrownBy(() -> PaymentRules.change(List.of(line("CREDIT", 5000), line("CREDIT", 5000)), 10000, null))
        .hasMessageContaining("only one credit");
    assertThatThrownBy(() -> PaymentRules.change(List.of(line("CASH", 3000), line("CREDIT", 3000)), 10000, null))
        .hasMessageContaining("add up");
  }

  @Test
  void creditIsNeverAllowedOnARefund() {
    assertThatThrownBy(() -> PaymentRules.checkRefund(List.of(line("CREDIT", 500)), 500)).hasMessageContaining("CASH, UPI or CARD");
  }

  @Test
  void creditAmountIsTheCreditShareOnly() {
    assertThat(PaymentRules.creditAmount(List.of(line("CASH", 3000), line("CREDIT", 7000)))).isEqualTo(7000);
    assertThat(PaymentRules.creditAmount(List.of(line("UPI", 3000)))).isZero();
    assertThat(PaymentRules.creditAmount(List.of())).isZero();
  }

  @Test
  void billsAreClassifiedByHowTheyWerePaid() {
    assertThat(PaymentRules.classify(List.of())).isEqualTo("NONE");
    assertThat(PaymentRules.classify(List.of(line("CASH", 1)))).isEqualTo("CASH");
    assertThat(PaymentRules.classify(List.of(line("UPI", 1)))).isEqualTo("UPI");
    assertThat(PaymentRules.classify(List.of(line("CASH", 1), line("UPI", 1)))).isEqualTo("SPLIT");
    assertThat(PaymentRules.classify(List.of(line("CREDIT", 1)))).isEqualTo("CREDIT");
    assertThat(PaymentRules.classify(List.of(line("CASH", 1), line("CREDIT", 1)))).isEqualTo("HYBRID");
    assertThat(PaymentRules.classify(List.of(line("CASH", 1), line("UPI", 1), line("CREDIT", 1)))).isEqualTo("HYBRID");
  }

  // ----- CreditMath -----
  @Test
  void availableCreditIsTheLimitMinusWhatIsOwedAndNeverNegative() {
    assertThat(CreditMath.available(10000, 3000)).isEqualTo(7000);
    assertThat(CreditMath.available(10000, 10000)).isZero();
    assertThat(CreditMath.available(10000, 12000)).isZero(); // limit was lowered below the balance
    assertThat(CreditMath.available(0, 0)).isZero();
  }

  @Test
  void aChargeFitsUpToTheAvailableCreditExactly() {
    assertThat(CreditMath.fits(10000, 3000, 7000)).isTrue();
    assertThat(CreditMath.fits(10000, 3000, 7001)).isFalse();
    assertThat(CreditMath.fits(0, 0, 1)).isFalse();
  }

  @Test
  void returnedGoodsFirstCancelTheCreditOnThatBill() {
    assertThat(CreditMath.returnAdjustment(9000, 0, 2250, 9000)).isEqualTo(2250);
  }

  @Test
  void aReturnCannotCancelMoreThanTheBillPutOnCredit() {
    assertThat(CreditMath.returnAdjustment(9000, 8000, 2250, 9000)).isEqualTo(1000);
    assertThat(CreditMath.returnAdjustment(9000, 9000, 2250, 9000)).isZero();
    assertThat(CreditMath.returnAdjustment(9000, 9500, 100, 9000)).isZero();
  }

  @Test
  void aReturnCannotCancelMoreThanTheCustomerStillOwes() {
    assertThat(CreditMath.returnAdjustment(9000, 0, 2250, 1000)).isEqualTo(1000);
    assertThat(CreditMath.returnAdjustment(9000, 0, 2250, 0)).isZero();
  }

  @Test
  void aReturnLargerThanTheCreditLeavesTheRestToBePaidOut() {
    assertThat(CreditMath.returnAdjustment(3000, 0, 5000, 9000)).isEqualTo(3000);
  }
}
