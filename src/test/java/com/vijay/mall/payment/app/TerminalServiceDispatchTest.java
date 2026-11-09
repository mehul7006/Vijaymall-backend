package com.vijay.mall.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.payment.app.TerminalService.SessionView;
import com.vijay.mall.payment.domain.CardTerminal;
import com.vijay.mall.payment.domain.CardTerminal.Attachment;
import com.vijay.mall.payment.domain.CardTerminal.Detail;
import com.vijay.mall.payment.domain.CardTerminal.Request;
import com.vijay.mall.payment.domain.CardTerminal.Session;
import com.vijay.mall.payment.infra.ManualTerminal;
import com.vijay.mall.payment.infra.MoneyAccountRepository.Account;
import com.vijay.mall.payment.infra.TerminalPaymentRepository;
import com.vijay.mall.payment.infra.TerminalPaymentRepository.Row;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The generic terminal service picks the machine by the chosen channel's provider and keeps one session id for the whole till journey. */
class TerminalServiceDispatchTest {
  /** A machine that keeps its own sessions, like the Paytm one: ids it knows are in {@code owned}. */
  private static final class OwnerTerminal implements CardTerminal {
    final java.util.Map<UUID, Session> owned = new java.util.HashMap<>();
    Request last;
    long answerAmount = -1;

    @Override public String provider() { return "owner"; }

    @Override public Session requestPayment(long amountPaise, String reference) { throw new IllegalStateException("needs a channel"); }

    @Override
    public Session requestPayment(Request r) {
      last = r;
      Session s = pending(UUID.randomUUID(), r.reference(), answerAmount < 0 ? r.amountPaise() : answerAmount);
      owned.put(s.id(), s);
      return s;
    }

    static Session pending(UUID id, String ref, long amount) {
      return new Session(id, ref, State.PENDING, Mode.PUSH, "go", null, null, null, null, new Detail(amount, null, "PENDING", "wait", "TID00001", "Counter", null,
          null, null, null, null, true, false, false, 3, null, Instant.EPOCH, null));
    }

    @Override public Session status(String reference) { throw new IllegalStateException(); }

    @Override public Session cancel(String reference) { throw new IllegalStateException(); }

    @Override public Optional<Session> find(UUID id) { return Optional.ofNullable(owned.get(id)); }

    @Override
    public Session cancel(UUID actor, UUID id) {
      Session s = owned.get(id);
      Session done = new Session(id, s.reference(), State.CANCELLED, Mode.PUSH, null, null, null, null, null, s.detail());
      owned.put(id, done);
      return done;
    }

    @Override public Session voidPayment(UUID actor, UUID id) { return owned.get(id); }

    @Override
    public Optional<Attachment> attach(UUID id, UUID saleId, long amountPaise, String mode, String field, UUID actor) {
      return owned.containsKey(id) ? Optional.of(new Attachment("RRN9", null, "TID00001", Map.of("provider", "owner"))) : Optional.empty();
    }
  }

  /** A machine that is told the result by the cashier or reports it itself, depending on {@code manual}. */
  private static final class FakeSimple implements CardTerminal {
    final String name;
    final boolean manual;
    State next = State.PENDING;

    FakeSimple(String name, boolean manual) {
      this.name = name;
      this.manual = manual;
    }

    @Override public String provider() { return name; }

    @Override public boolean manualConfirmation() { return manual; }

    @Override public Session requestPayment(long a, String ref) { return new Session(ref, State.PENDING, Mode.QR, "scan", "qr", null, null, null); }

    @Override public Session status(String ref) { return new Session(ref, next, Mode.QR, null, null, null, "AP", "RN"); }

    @Override public Session cancel(String ref) { return new Session(ref, State.PENDING, Mode.QR, null, null, null, null, null); }
  }

  private final TerminalPaymentRepository repo = mock(TerminalPaymentRepository.class);
  private final MoneyAccountService accounts = mock(MoneyAccountService.class);
  private final RuntimeSettings settings = mock(RuntimeSettings.class);
  private final OwnerTerminal owner = new OwnerTerminal();
  private final FakeSimple simple = new FakeSimple("simple", false);
  private final ManualTerminal manual = new ManualTerminal();
  private final TerminalService service = new TerminalService(repo, new CardTerminalRegistry(List.of(owner, simple, manual)), accounts, settings,
      mock(AuditService.class), Clock.fixed(Instant.parse("2026-11-09T05:00:00Z"), ZoneOffset.UTC));
  private final UUID actor = UUID.randomUUID();

  private Account account(String provider, String identifier) {
    return new Account(UUID.randomUUID(), "n", "CARD_TERMINAL", provider, identifier, null, true, "", Instant.EPOCH, Instant.EPOCH);
  }

  private Account channel(String provider, String identifier, boolean named) {
    Account a = account(provider, identifier);
    when(accounts.counterAccount("CARD", named ? a.id() : null, "moneyAccountId", false)).thenReturn(a.id());
    when(accounts.get(a.id())).thenReturn(a);
    return a;
  }

  private Row row(String provider, String state, UUID sale) {
    return new Row(UUID.randomUUID(), "REF", provider, 5000, state, "MANUAL", "do it", null, null, "AP", "RN", UUID.randomUUID(), Instant.EPOCH, sale);
  }

  @Test void aNamedPaytmStyleChannelIsServedByItsOwnAdapterWithoutAGenericRow() {
    when(settings.get(Settings.POS_TERMINAL_PROVIDER)).thenReturn("manual");
    Account a = channel("owner", "TID00001", true);
    SessionView v = service.request(actor, 5000, "INV/9", a.id());
    assertThat(owner.last.terminalId()).isEqualTo("TID00001");
    assertThat(owner.last.moneyAccountId()).isEqualTo(a.id());
    assertThat(v.provider()).isEqualTo("owner");
    assertThat(v.state()).isEqualTo("PENDING");
    assertThat(v.providerStatus()).isEqualTo("PENDING");
    assertThat(v.open()).isTrue();
    assertThat(v.terminalName()).isEqualTo("Counter");
    assertThat(v.pollAfterSeconds()).isEqualTo(3);
    verify(repo, never()).insert(any(), any(), any());
  }

  @Test void aNamedManualChannelUsesTheManualTerminalEvenWhenTheDefaultIsAnotherBrand() {
    when(settings.get(Settings.POS_TERMINAL_PROVIDER)).thenReturn("simple");
    Account a = channel("manual", "", true);
    when(repo.findByReference("R1")).thenReturn(Optional.empty());
    when(repo.insert(any(), any(), any())).thenReturn(true);
    SessionView v = service.request(actor, 5000, "R1", a.id());
    assertThat(v.provider()).isEqualTo("manual");
    assertThat(v.mode()).isEqualTo("MANUAL");
    assertThat(v.moneyAccountId()).isEqualTo(a.id());
    assertThat(v.canCancel()).isTrue();
    assertThat(v.terminalId()).isNull();
  }

  @Test void aChannelWhoseProviderHasNoAdapterFallsBackToTheDefaultProvider() {
    when(settings.get(Settings.POS_TERMINAL_PROVIDER)).thenReturn("simple");
    Account a = channel("razorpay", "", true);
    when(repo.findByReference("R2")).thenReturn(Optional.empty());
    when(repo.insert(any(), any(), any())).thenReturn(true);
    assertThat(service.request(actor, 5000, "R2", a.id()).provider()).isEqualTo("simple");
  }

  @Test void anImplicitDefaultAccountOfTheManualProviderDoesNotOverrideTheDefaultAdapter() {
    when(settings.get(Settings.POS_TERMINAL_PROVIDER)).thenReturn("simple");
    channel("manual", "", false);
    when(repo.findByReference(anyString())).thenReturn(Optional.empty());
    when(repo.insert(any(), any(), any())).thenReturn(true);
    assertThat(service.request(actor, 5000, null, null).provider()).isEqualTo("simple");
  }

  @Test void withNoAccountAtAllTheDefaultProviderIsUsedAndAReferenceIsMade() {
    when(settings.get(Settings.POS_TERMINAL_PROVIDER)).thenReturn("simple");
    when(accounts.counterAccount("CARD", null, "moneyAccountId", false)).thenReturn(null);
    when(repo.findByReference(anyString())).thenReturn(Optional.empty());
    when(repo.insert(any(), any(), any())).thenReturn(true);
    SessionView v = service.request(actor, 5000, " ", null);
    assertThat(v.reference()).startsWith("TP-");
    assertThat(v.qrPayload()).isEqualTo("qr");
  }

  @Test void aRetryReturnsTheSessionAndAReusedReferenceIsAConflict() {
    when(settings.get(Settings.POS_TERMINAL_PROVIDER)).thenReturn("simple");
    Row old = row("simple", "PENDING", null);
    when(accounts.counterAccount("CARD", null, "moneyAccountId", false)).thenReturn(null);
    when(repo.findByReference("REF")).thenReturn(Optional.of(old));
    assertThat(service.request(actor, 5000, "REF", null).id()).isEqualTo(old.id());
    assertThatThrownBy(() -> service.request(actor, 6000, "REF", null)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("REFERENCE_REUSED"));
    when(settings.get(Settings.POS_TERMINAL_PROVIDER)).thenReturn("manual");
    assertThatThrownBy(() -> service.request(actor, 5000, "REF", null)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("REFERENCE_REUSED"));
  }

  @Test void aParallelRetryThatWonTheInsertIsReturned() {
    when(settings.get(Settings.POS_TERMINAL_PROVIDER)).thenReturn("simple");
    when(accounts.counterAccount("CARD", null, "moneyAccountId", false)).thenReturn(null);
    Row winner = row("simple", "PENDING", null);
    when(repo.findByReference("REF")).thenReturn(Optional.empty(), Optional.of(winner));
    when(repo.insert(any(), any(), any())).thenReturn(false);
    assertThat(service.request(actor, 5000, "REF", null).id()).isEqualTo(winner.id());
  }

  @Test void anOwnedSessionAnsweringAnotherAmountIsAReusedReference() {
    when(settings.get(Settings.POS_TERMINAL_PROVIDER)).thenReturn("simple");
    Account a = channel("owner", "", true);
    owner.answerAmount = 1;
    assertThatThrownBy(() -> service.request(actor, 5000, "OLD", a.id())).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("REFERENCE_REUSED"));
  }

  @Test void badAmountOrReferenceIsRefused() {
    assertThatThrownBy(() -> service.request(actor, 0, "r", null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.request(actor, TerminalService.MAX_PAISE + 1, "r", null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.request(actor, 100, "x".repeat(61), null)).isInstanceOf(ApiException.class);
  }

  @Test void getReadsAnOwnedSessionAnOrdinaryRowAndRefreshesAPendingReportingOne() {
    UUID owned = UUID.randomUUID();
    owner.owned.put(owned, OwnerTerminal.pending(owned, "R", 100));
    assertThat(service.get(owned).provider()).isEqualTo("owner");
    assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(ApiException.class);

    Row done = row("simple", "APPROVED", null);
    when(repo.find(done.id())).thenReturn(Optional.of(done));
    assertThat(service.get(done.id()).state()).isEqualTo("APPROVED");

    Row manualRow = row("manual", "PENDING", null);
    when(repo.find(manualRow.id())).thenReturn(Optional.of(manualRow));
    assertThat(service.get(manualRow.id()).state()).isEqualTo("PENDING");

    Row pending = row("simple", "PENDING", null);
    when(repo.find(pending.id())).thenReturn(Optional.of(pending), Optional.of(row("simple", "APPROVED", null)));
    simple.next = CardTerminal.State.APPROVED;
    assertThat(service.get(pending.id()).state()).isEqualTo("APPROVED");
    verify(repo).closePending(any(), anyString(), anyString(), anyString(), any());

    Row still = row("simple", "PENDING", null);
    when(repo.find(still.id())).thenReturn(Optional.of(still));
    simple.next = CardTerminal.State.PENDING;
    assertThat(service.get(still.id()).state()).isEqualTo("PENDING");
  }

  @Test void confirmIsRefusedForAnOwnedOrSelfReportingMachineAndUnknownIdsAreNotFound() {
    UUID owned = UUID.randomUUID();
    owner.owned.put(owned, OwnerTerminal.pending(owned, "R", 100));
    assertThatThrownBy(() -> service.confirm(actor, owned, "AP", null)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TERMINAL_CONFIRMS_ITSELF"));
    assertThatThrownBy(() -> service.confirm(actor, UUID.randomUUID(), "AP", null)).isInstanceOf(ApiException.class);
    Row selfReporting = row("simple", "PENDING", null);
    when(repo.find(selfReporting.id())).thenReturn(Optional.of(selfReporting));
    assertThatThrownBy(() -> service.confirm(actor, selfReporting.id(), "AP", null)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TERMINAL_CONFIRMS_ITSELF"));
    assertThatThrownBy(() -> service.confirm(actor, owned, " ", null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.confirm(actor, owned, "AP", "x".repeat(41))).isInstanceOf(ApiException.class);
  }

  @Test void confirmOfAManualRowApprovesItOnceAndRefusesAClosedOne() {
    Row pending = row("manual", "PENDING", null);
    when(repo.find(pending.id())).thenReturn(Optional.of(pending), Optional.of(row("manual", "APPROVED", null)));
    when(repo.closePending(any(), anyString(), anyString(), any(), any())).thenReturn(true);
    assertThat(service.confirm(actor, pending.id(), "AP1", "R1").state()).isEqualTo("APPROVED");
    Row approved = row("manual", "APPROVED", null);
    when(repo.find(approved.id())).thenReturn(Optional.of(approved));
    assertThat(service.confirm(actor, approved.id(), "AP1", null).state()).isEqualTo("APPROVED");
    Row cancelled = row("manual", "CANCELLED", null);
    when(repo.find(cancelled.id())).thenReturn(Optional.of(cancelled));
    when(repo.closePending(any(), anyString(), anyString(), any(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.confirm(actor, cancelled.id(), "AP1", " ")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TERMINAL_PAYMENT_CLOSED"));
  }

  @Test void cancelWorksOnOwnedAndOrdinarySessions() {
    UUID owned = UUID.randomUUID();
    owner.owned.put(owned, OwnerTerminal.pending(owned, "R", 100));
    assertThat(service.cancel(actor, owned).state()).isEqualTo("CANCELLED");
    assertThatThrownBy(() -> service.cancel(actor, UUID.randomUUID())).isInstanceOf(ApiException.class);
    Row over = row("manual", "APPROVED", null);
    when(repo.find(over.id())).thenReturn(Optional.of(over));
    assertThat(service.cancel(actor, over.id()).state()).isEqualTo("APPROVED");
    Row pending = row("manual", "PENDING", null);
    when(repo.find(pending.id())).thenReturn(Optional.of(pending), Optional.of(row("manual", "CANCELLED", null)));
    assertThat(service.cancel(actor, pending.id()).state()).isEqualTo("CANCELLED");
    verify(repo).closePending(any(), anyString(), any(), any(), any());
    Row open = row("simple", "PENDING", null);
    when(repo.find(open.id())).thenReturn(Optional.of(open), Optional.of(row("simple", "CANCELLED", null)));
    assertThat(service.cancel(actor, open.id()).state()).isEqualTo("CANCELLED");
  }

  @Test void voidIsForOwnedSessionsOnly() {
    UUID owned = UUID.randomUUID();
    owner.owned.put(owned, OwnerTerminal.pending(owned, "R", 100));
    assertThat(service.voidPayment(actor, owned).provider()).isEqualTo("owner");
    Row ordinary = row("manual", "APPROVED", null);
    when(repo.find(ordinary.id())).thenReturn(Optional.of(ordinary));
    assertThatThrownBy(() -> service.voidPayment(actor, ordinary.id())).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_VOIDABLE"));
    assertThatThrownBy(() -> service.voidPayment(actor, UUID.randomUUID())).isInstanceOf(ApiException.class);
  }

  @Test void attachResolvesEitherKindOfSessionAndEnforcesTheBillRules() {
    UUID sale = UUID.randomUUID();
    Row ok = row("manual", "APPROVED", null);
    when(repo.lock(ok.id())).thenReturn(Optional.of(ok));
    Attachment a = service.attach(ok.id(), sale, 5000, "CARD", "f", actor);
    assertThat(a.reference()).isEqualTo("RN");
    assertThat(a.moneyAccountId()).isEqualTo(ok.moneyAccountId());
    assertThat(a.detail()).containsEntry("authCode", "AP").containsEntry("rrn", "RN").containsEntry("provider", "manual");
    verify(repo).markUsed(any(), any(), any());

    Row noRrn = new Row(UUID.randomUUID(), "R", "manual", 5000, "APPROVED", "MANUAL", null, null, null, "AP9", null, null, Instant.EPOCH, null);
    when(repo.lock(noRrn.id())).thenReturn(Optional.of(noRrn));
    Attachment b = service.attach(noRrn.id(), sale, 5000, "CARD", "f", actor);
    assertThat(b.reference()).isEqualTo("AP9");
    assertThat(b.detail()).doesNotContainKey("rrn");

    Row unpaid = row("manual", "PENDING", null);
    when(repo.lock(unpaid.id())).thenReturn(Optional.of(unpaid));
    assertThatThrownBy(() -> service.attach(unpaid.id(), sale, 5000, "CARD", "f", actor)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TERMINAL_PAYMENT_NOT_PAID"));
    Row used = row("manual", "APPROVED", UUID.randomUUID());
    when(repo.lock(used.id())).thenReturn(Optional.of(used));
    assertThatThrownBy(() -> service.attach(used.id(), sale, 5000, "CARD", "f", actor)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TERMINAL_PAYMENT_USED"));
    assertThatThrownBy(() -> service.attach(ok.id(), sale, 4000, "CARD", "f", actor)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TERMINAL_PAYMENT_AMOUNT"));

    UUID owned = UUID.randomUUID();
    owner.owned.put(owned, OwnerTerminal.pending(owned, "R", 100));
    assertThat(service.attach(owned, sale, 100, "CARD", "f", actor).reference()).isEqualTo("RRN9");
    assertThatThrownBy(() -> service.attach(UUID.randomUUID(), sale, 100, "CARD", "payments[0].terminalPaymentId", actor)).isInstanceOf(ApiException.class);
  }
}
