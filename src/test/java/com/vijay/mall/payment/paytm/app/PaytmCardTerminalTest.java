package com.vijay.mall.payment.paytm.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.CardTerminal;
import com.vijay.mall.payment.domain.CardTerminal.Attachment;
import com.vijay.mall.payment.domain.CardTerminal.Request;
import com.vijay.mall.payment.domain.CardTerminal.Session;
import com.vijay.mall.payment.paytm.PaytmTestSettings;
import com.vijay.mall.payment.paytm.app.TerminalPaymentService.Confirmed;
import com.vijay.mall.payment.paytm.app.TerminalPaymentService.TerminalPaymentView;
import com.vijay.mall.payment.paytm.infra.PaymentTerminalEntity;
import com.vijay.mall.payment.paytm.infra.PaymentTerminalJpa;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The Paytm machine as a till terminal: which machine a request picks, how every Paytm state reads in the generic session, and the bill attachment. */
class PaytmCardTerminalTest {
  private final TerminalPaymentService payments = mock(TerminalPaymentService.class);
  private final PaymentTerminalJpa terminals = mock(PaymentTerminalJpa.class);
  private final PaytmCardTerminal terminal = new PaytmCardTerminal(payments, terminals, PaytmTestSettings.of(Map.of()));
  private final UUID actor = UUID.randomUUID();
  private final UUID rowId = UUID.randomUUID();
  private final UUID accountId = UUID.randomUUID();

  private PaymentTerminalEntity machine() {
    PaymentTerminalEntity t = PaymentTerminalEntity.create();
    t.id = rowId;
    t.terminalId = "ABCD1234";
    t.provider = "paytm";
    t.name = "Counter 1";
    t.active = true;
    t.moneyAccountId = accountId;
    return t;
  }

  private TerminalPaymentView view(String status, boolean open, boolean cancelOnMachine) {
    return new TerminalPaymentView(UUID.randomUUID(), rowId, "ABCD1234", "Counter 1", 25000, "INV/1", status, open, "msg", "0000", "CREDIT_CARD", "VISA",
        "411111XXXXXX1111", "412345678901", "A1B2C3", "HDFC Bank", "cu***@okbank", null, open, cancelOnMachine, false, 3, Instant.EPOCH, open ? null : Instant.EPOCH);
  }

  @Test void identifiesItselfAsAPushTerminalThatReportsItsOwnResult() {
    assertThat(terminal.provider()).isEqualTo("paytm");
    assertThat(terminal.manualConfirmation()).isFalse();
    assertThat(terminal.configured()).isFalse(); // the test settings carry no merchant id
    assertThat(PaytmCardTerminal.idemKey("INV/1")).isEqualTo("pos:INV/1");
    assertThatThrownBy(() -> terminal.requestPayment(100, "r")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details().toString()).contains("moneyAccountId"));
  }

  @Test void configuredWhenTheMerchantDetailsAreSaved() {
    var ready = new PaytmCardTerminal(payments, terminals, PaytmTestSettings.of(Map.of("paytm.enabled", "true", "paytm.mid", "M", "paytm.merchantKey", "0123456789abcdef", "paytm.channelId", "EDC")));
    assertThat(ready.configured()).isTrue();
  }

  @Test void startUsesTheMachineOfTheChosenAccountAndKeysThePaymentByTheTillReference() {
    when(terminals.findByMoneyAccountId(accountId)).thenReturn(Optional.of(machine()));
    when(terminals.findById(rowId)).thenReturn(Optional.of(machine()));
    TerminalPaymentView started = view("PENDING", true, false);
    when(payments.start(actor, "pos:INV/1", rowId, 25000, "INV/1")).thenReturn(started);
    Session s = terminal.requestPayment(new Request(actor, 25000, "INV/1", accountId, "ABCD1234"));
    assertThat(s.id()).isEqualTo(started.id());
    assertThat(s.state()).isEqualTo(CardTerminal.State.PENDING);
    assertThat(s.mode()).isEqualTo(CardTerminal.Mode.PUSH);
    assertThat(s.instructions()).isEqualTo("msg");
    assertThat(s.detail().moneyAccountId()).isEqualTo(accountId);
    assertThat(s.detail().providerStatus()).isEqualTo("PENDING");
    assertThat(s.detail().terminalName()).isEqualTo("Counter 1");
  }

  @Test void startFindsTheMachineByTerminalIdOrTheOnlyActiveOne() {
    when(terminals.findByProviderAndTerminalId("paytm", "ABCD1234")).thenReturn(Optional.of(machine()));
    when(terminals.findByProviderAndActiveTrueOrderByNameAsc("paytm")).thenReturn(List.of(machine()));
    when(payments.start(any(), any(), eq(rowId), anyLong(), any())).thenReturn(view("PENDING", true, false));
    assertThat(terminal.requestPayment(new Request(actor, 100, "r1", null, " ABCD1234 ")).detail().terminalId()).isEqualTo("ABCD1234");
    assertThat(terminal.requestPayment(new Request(actor, 100, "r2", null, null)).detail().terminalId()).isEqualTo("ABCD1234");
    assertThat(terminal.requestPayment(new Request(actor, 100, "r3", null, " ")).detail().terminalId()).isEqualTo("ABCD1234");
  }

  @Test void startRefusesAnUnknownMachineOrAnAmbiguousChoice() {
    when(terminals.findByMoneyAccountId(accountId)).thenReturn(Optional.empty());
    when(terminals.findByProviderAndTerminalId("paytm", "NOPE0000")).thenReturn(Optional.empty());
    when(terminals.findByProviderAndActiveTrueOrderByNameAsc("paytm")).thenReturn(List.of(machine(), machine()));
    assertThatThrownBy(() -> terminal.requestPayment(new Request(actor, 100, "r", accountId, null))).isInstanceOf(ApiException.class).hasMessageContaining("not a Paytm");
    assertThatThrownBy(() -> terminal.requestPayment(new Request(actor, 100, "r", null, "NOPE0000"))).isInstanceOf(ApiException.class).hasMessageContaining("unknown card machine");
    assertThatThrownBy(() -> terminal.requestPayment(new Request(actor, 100, "r", null, null))).isInstanceOf(ApiException.class).hasMessageContaining("choose which");
    when(terminals.findByProviderAndActiveTrueOrderByNameAsc("paytm")).thenReturn(List.of());
    assertThatThrownBy(() -> terminal.requestPayment(new Request(actor, 100, "r", null, null))).isInstanceOf(ApiException.class);
  }

  @ParameterizedTest
  @CsvSource({"REQUESTED,PENDING,true", "PENDING,PENDING,true", "UNKNOWN,PENDING,true", "SUCCESS,APPROVED,false", "FAILED,DECLINED,false",
      "CANCELLED,CANCELLED,false", "VOIDED,CANCELLED,false", "EXPIRED,EXPIRED,false"})
  void everyPaytmStateReadsInTheGenericSessionAndKeepsItsOwnWord(String paytm, CardTerminal.State generic, boolean open) {
    when(terminals.findById(rowId)).thenReturn(Optional.of(machine()));
    Session s = terminal.session(view(paytm, open, false));
    assertThat(s.state()).isEqualTo(generic);
    assertThat(s.detail().providerStatus()).isEqualTo(paytm);
    assertThat(s.instructions()).isEqualTo(open ? "msg" : null);
    assertThat(s.rrn()).isEqualTo("412345678901");
    assertThat(s.approvalCode()).isEqualTo("A1B2C3");
  }

  @Test void aCancelRequestedOnAnOpenPaymentIsShownAsCancelOnMachine() {
    when(terminals.findById(rowId)).thenReturn(Optional.of(machine()));
    Session s = terminal.session(view("PENDING", true, true));
    assertThat(s.detail().cancelOnMachine()).isTrue();
    assertThat(s.detail().canCancel()).isTrue();
  }

  @Test void statusAndCancelByReferenceFindThePaymentAgainByItsKey() {
    TerminalPaymentView v = view("PENDING", true, false);
    when(terminals.findById(rowId)).thenReturn(Optional.of(machine()));
    when(payments.byIdemKey("pos:INV/1")).thenReturn(Optional.of(v));
    when(payments.byIdemKey("pos:none")).thenReturn(Optional.empty());
    when(payments.get(v.id())).thenReturn(v);
    when(payments.cancel(null, v.id())).thenReturn(view("CANCELLED", false, false));
    assertThat(terminal.status("INV/1").id()).isEqualTo(v.id());
    assertThat(terminal.cancel("INV/1").state()).isEqualTo(CardTerminal.State.CANCELLED);
    assertThatThrownBy(() -> terminal.status("none")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> terminal.cancel("none")).isInstanceOf(ApiException.class);
  }

  @Test void findCancelAndVoidWorkOnTheSessionId() {
    TerminalPaymentView v = view("SUCCESS", false, false);
    when(terminals.findById(rowId)).thenReturn(Optional.of(machine()));
    when(payments.find(v.id())).thenReturn(Optional.of(v));
    when(payments.find(any(UUID.class))).thenReturn(Optional.empty());
    when(payments.find(v.id())).thenReturn(Optional.of(v));
    when(payments.cancel(actor, v.id())).thenReturn(view("CANCELLED", false, false));
    when(payments.voidPayment(actor, v.id())).thenReturn(view("VOIDED", false, false));
    assertThat(terminal.find(v.id())).isPresent();
    assertThat(terminal.find(UUID.randomUUID())).isEmpty();
    assertThat(terminal.cancel(actor, v.id()).detail().providerStatus()).isEqualTo("CANCELLED");
    assertThat(terminal.voidPayment(actor, v.id()).detail().providerStatus()).isEqualTo("VOIDED");
  }

  @Test void attachGivesTheMachineAccountTheRrnAndTheMaskedPayerDetail() {
    UUID id = UUID.randomUUID();
    UUID sale = UUID.randomUUID();
    when(payments.exists(id)).thenReturn(true);
    when(terminals.findById(rowId)).thenReturn(Optional.of(machine()));
    when(payments.attach(id, sale, 25000, "CARD", "payments[0].terminalPaymentId", actor)).thenReturn(
        new Confirmed(id, 25000, "CREDIT_CARD", "VISA", "411111XXXXXX1111", "412345678901", "A1B2C3", "ABCD1234", "412345678901", "HDFC Bank", null, rowId));
    Attachment a = terminal.attach(id, sale, 25000, "CARD", "payments[0].terminalPaymentId", actor).orElseThrow();
    assertThat(a.reference()).isEqualTo("412345678901");
    assertThat(a.moneyAccountId()).isEqualTo(accountId);
    assertThat(a.terminalId()).isEqualTo("ABCD1234");
    assertThat(a.detail()).containsEntry("provider", "paytm").containsEntry("cardLast4", "1111").containsEntry("cardScheme", "VISA")
        .containsEntry("issuingBank", "HDFC Bank").containsEntry("authCode", "A1B2C3").containsEntry("rrn", "412345678901").doesNotContainKey("upiVpa");
  }

  @Test void attachOfAPaymentWithoutCardNumberAndOfAnUnknownIdIsSafe() {
    UUID id = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    when(payments.exists(id)).thenReturn(true);
    when(payments.exists(other)).thenReturn(false);
    when(terminals.findById(rowId)).thenReturn(Optional.empty());
    when(payments.attach(any(), any(), anyLong(), any(), any(), any())).thenReturn(
        new Confirmed(id, 100, "UPI", null, "no digits", null, null, "ABCD1234", "PAYTM abc", null, "cu***@okbank", rowId));
    Attachment a = terminal.attach(id, UUID.randomUUID(), 100, "UPI", "f", actor).orElseThrow();
    assertThat(a.moneyAccountId()).isNull();
    assertThat(a.detail()).containsEntry("upiVpa", "cu***@okbank").doesNotContainKeys("cardLast4", "rrn", "cardScheme");
    assertThat(terminal.attach(other, UUID.randomUUID(), 100, "UPI", "f", actor)).isEmpty();
  }
}
