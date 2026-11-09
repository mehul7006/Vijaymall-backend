package com.vijay.mall.payment.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.payment.domain.CardTerminal;
import com.vijay.mall.payment.domain.CardTerminal.Attachment;
import com.vijay.mall.payment.domain.CardTerminal.Detail;
import com.vijay.mall.payment.domain.CardTerminal.Request;
import com.vijay.mall.payment.domain.CardTerminal.Session;
import com.vijay.mall.payment.infra.MoneyAccountRepository.Account;
import com.vijay.mall.payment.infra.TerminalPaymentRepository;
import com.vijay.mall.payment.infra.TerminalPaymentRepository.Row;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts, follows and ends card or UPI machine payments for the till. The adapter is chosen by the channel the cashier picked: the
 * provider of its money account decides (a manual machine, a Paytm machine, any other brand with an adapter). The {@code pos.terminalProvider}
 * setting is only the default for a channel whose provider has no adapter, or when no channel was named.
 *
 * <p>One session id serves the whole till journey. An adapter that keeps sessions itself (Paytm) owns the row and its id is returned as it is;
 * for the other adapters this service keeps a row in {@code card_terminal_session}. Either id goes on the bill line as
 * {@code terminalPaymentId}, and {@link #attach} resolves it.
 */
@Service
public class TerminalService {
  /** Largest amount one terminal payment may ask for: 10 lakh rupees, like every money input at the till. */
  public static final long MAX_PAISE = 100_000_000L;

  /**
   * A payment session as the till sees it, whichever machine took it.
   * @param state PENDING, APPROVED, DECLINED, CANCELLED or EXPIRED
   * @param providerStatus the machine vendor's own word (Paytm: REQUESTED, PENDING, UNKNOWN, SUCCESS, FAILED, CANCELLED, EXPIRED, VOIDED); equals {@code state} for simple adapters
   * @param open true while the till should keep polling
   * @param message plain text for the cashier
   * @param terminalId the vendor's terminal id, when the machine has one
   * @param canCancel true while a cancel can be tried
   * @param cancelOnMachine true when the cashier must press cancel on the machine
   * @param canVoid true when a same-day void is possible
   * @param pollAfterSeconds how long to wait before asking again
   * @param posSaleId the bill that used it, or null
   */
  @Schema(name = "TerminalPaymentRow")
  public record SessionView(UUID id, String reference, String provider, long amountPaise, String state, String providerStatus, String mode, boolean open,
                            String instructions, String message, String qrPayload, String qrImageUrl, String approvalCode, String rrn, UUID moneyAccountId,
                            String terminalId, String terminalName, String payMethod, String cardScheme, String maskedCard, String issuingBank,
                            String upiVpa, boolean canCancel, boolean cancelOnMachine, boolean canVoid, int pollAfterSeconds, UUID posSaleId,
                            Instant createdAt, Instant finishedAt) {}

  private static final int SIMPLE_POLL_SECONDS = 3;

  private final TerminalPaymentRepository repo;
  private final CardTerminalRegistry registry;
  private final MoneyAccountService accounts;
  private final RuntimeSettings settings;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public TerminalService(TerminalPaymentRepository repo, CardTerminalRegistry registry, MoneyAccountService accounts, RuntimeSettings settings,
                         AuditService audit, Clock clock) {
    this.repo = repo;
    this.registry = registry;
    this.accounts = accounts;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
  }

  private static SessionView view(Row r) {
    boolean open = "PENDING".equals(r.state());
    return new SessionView(r.id(), r.reference(), r.provider(), r.amountPaise(), r.state(), r.state(), r.mode(), open, r.instructions(), r.instructions(),
        r.qrPayload(), r.qrImageUrl(), r.approvalCode(), r.rrn(), r.moneyAccountId(), null, null, null, null, null, null, null, open, false, false,
        SIMPLE_POLL_SECONDS, r.posSaleId(), r.createdAt(), null);
  }

  private static SessionView view(String provider, Session s) {
    Detail d = s.detail();
    return new SessionView(s.id(), s.reference(), provider, d.amountPaise(), s.state().name(), d.providerStatus(), s.mode().name(),
        s.state() == CardTerminal.State.PENDING, s.instructions(), d.message(), s.qrPayload(), s.qrImageUrl(), s.approvalCode(), s.rrn(), d.moneyAccountId(),
        d.terminalId(), d.terminalName(), d.payMethod(), d.cardScheme(), d.maskedCard(), d.issuingBank(), d.upiVpa(), d.canCancel(), d.cancelOnMachine(),
        d.canVoid(), d.pollAfterSeconds(), d.posSaleId(), d.createdAt(), d.finishedAt());
  }

  /** The adapter for a channel: its account's provider when an adapter of that name exists, else the default provider. */
  private CardTerminal terminalFor(Account channel, boolean named) {
    boolean own = channel != null && registry.knows(channel.provider()) && (named || !"manual".equals(channel.provider()));
    return registry.byProvider(own ? channel.provider() : settings.get(Settings.POS_TERMINAL_PROVIDER));
  }

  /** The adapter and the session an adapter keeps itself for an id, if any. */
  private record Owned(CardTerminal terminal, Session session) {}

  private Optional<Owned> owned(UUID id) {
    for (CardTerminal t : registry.all()) {
      Optional<Session> s = t.find(id);
      if (s.isPresent()) return Optional.of(new Owned(t, s.get()));
    }
    return Optional.empty();
  }

  /**
   * Asks the chosen machine to collect an amount. The same reference with the same amount returns the session already started (a retry);
   * the same reference with another amount, or on another machine, is a conflict.
   *
   * @param reference the till's own reference for this payment, or null to have one made
   * @param moneyAccountId the channel's account (the machine to use), or null for the shop's default card account and default adapter
   * @throws ApiException 400 on a bad amount, reference or account; 409 {@code REFERENCE_REUSED}; 409 {@code TERMINAL_BUSY} when a Paytm machine has
   *     a payment waiting; 422 {@code TERMINAL_REJECTED}; 503 {@code TERMINAL_NOT_CONFIGURED}
   */
  public SessionView request(UUID actor, long amountPaise, String reference, UUID moneyAccountId) {
    if (amountPaise <= 0 || amountPaise > MAX_PAISE) throw ApiException.validation("amountPaise", "must be between 1 and " + MAX_PAISE);
    String ref = reference == null || reference.isBlank() ? "TP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : reference.strip();
    if (ref.length() > 60) throw ApiException.validation("reference", "at most 60 characters");
    UUID account = accounts.counterAccount("CARD", moneyAccountId, "moneyAccountId", false);
    Account channel = account == null ? null : accounts.get(account);
    CardTerminal t = terminalFor(channel, moneyAccountId != null);
    var existing = repo.findByReference(ref);
    if (existing.isPresent()) {
      if (existing.get().amountPaise() != amountPaise || !existing.get().provider().equals(t.provider())) {
        throw ApiException.conflict("REFERENCE_REUSED", "This reference was used for another payment", Map.of("fields", Map.of("reference", "already used")));
      }
      return view(existing.get());
    }
    Session s = t.requestPayment(new Request(actor, amountPaise, ref, account, channel == null || channel.identifier().isEmpty() ? null : channel.identifier()));
    if (s.id() != null) {
      if (s.detail().amountPaise() != amountPaise) {
        throw ApiException.conflict("REFERENCE_REUSED", "This reference was used for another payment", Map.of("fields", Map.of("reference", "already used")));
      }
      audit.log(actor, "TERMINAL_PAYMENT", "terminal_payment", s.id(), ref + " amountPaise=" + amountPaise);
      return view(t.provider(), s);
    }
    Row row = new Row(UUID.randomUUID(), ref, t.provider(), amountPaise, s.state().name(), s.mode().name(), s.instructions(), s.qrPayload(), s.qrImageUrl(),
        s.approvalCode(), s.rrn(), account, clock.instant(), null);
    if (!repo.insert(row, actor, clock.instant())) return view(repo.findByReference(ref).orElseThrow()); // a parallel retry won
    audit.log(actor, "TERMINAL_PAYMENT", "terminal_payment", row.id(), ref + " amountPaise=" + amountPaise);
    return view(row);
  }

  /** The session as it stands; a pending one is refreshed from the terminal when the adapter reports results itself. */
  public SessionView get(UUID id) {
    Optional<Row> found = repo.find(id);
    if (found.isEmpty()) {
      Owned o = owned(id).orElseThrow(() -> ApiException.notFound("Terminal payment"));
      return view(o.terminal().provider(), o.session());
    }
    Row r = found.get();
    if (!"PENDING".equals(r.state())) return view(r);
    CardTerminal t = registry.byProvider(r.provider());
    if (t.manualConfirmation()) return view(r);
    Session s = t.status(r.reference());
    if (s.state() != CardTerminal.State.PENDING) repo.closePending(id, s.state().name(), s.approvalCode(), s.rrn(), clock.instant());
    return view(repo.find(id).orElseThrow());
  }

  /**
   * The cashier says the machine approved: stores the approval code and RRN. Only for adapters that cannot report the result themselves.
   * Confirming an approved payment again returns it unchanged.
   *
   * @throws ApiException 400 when the code is blank; 409 {@code TERMINAL_PAYMENT_CLOSED} when it was cancelled or declined; 409
   *     {@code TERMINAL_CONFIRMS_ITSELF} for a machine that reports the result itself
   */
  public SessionView confirm(UUID actor, UUID id, String approvalCode, String rrn) {
    String code = approvalCode == null ? "" : approvalCode.strip();
    if (code.isEmpty() || code.length() > 40) throw ApiException.validation("approvalCode", "1 to 40 characters");
    if (rrn != null && rrn.strip().length() > 40) throw ApiException.validation("rrn", "at most 40 characters");
    Optional<Row> found = repo.find(id);
    if (found.isEmpty()) {
      if (owned(id).isPresent()) throw confirmsItself();
      throw ApiException.notFound("Terminal payment");
    }
    Row r = found.get();
    if (!registry.byProvider(r.provider()).manualConfirmation()) throw confirmsItself();
    if ("APPROVED".equals(r.state())) return view(r);
    if (!repo.closePending(id, "APPROVED", code, rrn == null || rrn.isBlank() ? null : rrn.strip(), clock.instant())) {
      throw ApiException.conflict("TERMINAL_PAYMENT_CLOSED", "This payment was already " + r.state().toLowerCase(), Map.of());
    }
    audit.log(actor, "TERMINAL_PAYMENT_CONFIRM", "terminal_payment", id, r.reference());
    return view(repo.find(id).orElseThrow());
  }

  private static ApiException confirmsItself() { return ApiException.conflict("TERMINAL_CONFIRMS_ITSELF", "This terminal reports the result itself", Map.of()); }

  /**
   * Cancels a pending session; a session that is already over is returned as it is.
   *
   * @throws ApiException 409 {@code CANCEL_ON_MACHINE} or {@code ALREADY_PAID} for a Paytm machine (see its guide)
   */
  public SessionView cancel(UUID actor, UUID id) {
    Optional<Row> found = repo.find(id);
    if (found.isEmpty()) {
      Owned o = owned(id).orElseThrow(() -> ApiException.notFound("Terminal payment"));
      return view(o.terminal().provider(), o.terminal().cancel(actor, id));
    }
    Row r = found.get();
    if (!"PENDING".equals(r.state())) return view(r);
    Session s = registry.byProvider(r.provider()).cancel(r.reference());
    repo.closePending(id, s.state() == CardTerminal.State.PENDING ? "CANCELLED" : s.state().name(), null, null, clock.instant());
    audit.log(actor, "TERMINAL_PAYMENT_CANCEL", "terminal_payment", id, r.reference());
    return view(repo.find(id).orElseThrow());
  }

  /**
   * Voids a paid payment taken the same day, for machines that can (Paytm).
   *
   * @throws ApiException 409 {@code NOT_VOIDABLE} when the machine cannot void
   */
  public SessionView voidPayment(UUID actor, UUID id) {
    if (repo.find(id).isPresent()) throw ApiException.conflict("NOT_VOIDABLE", "This machine cannot void a payment from here", Map.of());
    Owned o = owned(id).orElseThrow(() -> ApiException.notFound("Terminal payment"));
    return view(o.terminal().provider(), o.terminal().voidPayment(actor, id));
  }

  /**
   * Uses a paid session on a counter bill, inside the bill's transaction. The session must be paid (APPROVED), unused and of the line's
   * amount; a Paytm payment must also fit the line's mode.
   *
   * @param field the request field named in errors, for example {@code payments[1].terminalPaymentId}
   * @throws ApiException 400 for an unknown id; 422 {@code TERMINAL_PAYMENT_NOT_PAID}, {@code TERMINAL_PAYMENT_AMOUNT}; 409 {@code TERMINAL_PAYMENT_USED}
   */
  @Transactional
  public Attachment attach(UUID id, UUID saleId, long amountPaise, String mode, String field, UUID actor) {
    Optional<Row> locked = repo.lock(id);
    if (locked.isPresent()) return attachRow(locked.get(), saleId, amountPaise, field);
    for (CardTerminal t : registry.all()) {
      Optional<Attachment> a = t.attach(id, saleId, amountPaise, mode, field, actor);
      if (a.isPresent()) return a.get();
    }
    throw ApiException.validation(field, "unknown card machine payment");
  }

  private Attachment attachRow(Row r, UUID saleId, long amountPaise, String field) {
    if (!"APPROVED".equals(r.state())) {
      throw ApiException.unprocessable("TERMINAL_PAYMENT_NOT_PAID", "This card machine payment is not paid (it is " + r.state() + ")",
          Map.of("fields", Map.of(field, "payment is not paid")));
    }
    if (r.posSaleId() != null) {
      throw ApiException.conflict("TERMINAL_PAYMENT_USED", "This card machine payment was already used for another bill", Map.of("fields", Map.of(field, "payment already used")));
    }
    if (r.amountPaise() != amountPaise) {
      throw ApiException.unprocessable("TERMINAL_PAYMENT_AMOUNT", "The card machine took " + r.amountPaise() + " paise but this line is for a different amount",
          Map.of("fields", Map.of(field, "amount must equal the card machine payment of " + r.amountPaise() + " paise")));
    }
    repo.markUsed(r.id(), saleId, clock.instant());
    Map<String, String> detail = new LinkedHashMap<>();
    detail.put("provider", r.provider());
    if (r.approvalCode() != null) detail.put("authCode", r.approvalCode());
    if (r.rrn() != null) detail.put("rrn", r.rrn());
    return new Attachment(r.rrn() != null ? r.rrn() : r.approvalCode(), r.moneyAccountId(), null, detail);
  }
}
