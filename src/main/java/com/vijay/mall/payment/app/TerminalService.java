package com.vijay.mall.payment.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.payment.domain.CardTerminal;
import com.vijay.mall.payment.domain.CardTerminal.Session;
import com.vijay.mall.payment.infra.TerminalPaymentRepository;
import com.vijay.mall.payment.infra.TerminalPaymentRepository.Row;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Starts, follows and ends card or UPI machine payments for the till, through whichever {@link CardTerminal} adapter the shop selected. */
@Service
public class TerminalService {
  /** Largest amount one terminal payment may ask for: 10 lakh rupees, like every money input at the till. */
  public static final long MAX_PAISE = 100_000_000L;

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

  private CardTerminal terminal() { return registry.byProvider(settings.get(Settings.POS_TERMINAL_PROVIDER)); }

  /**
   * Asks the terminal to collect an amount. The same reference with the same amount returns the session already started (a retry); the same
   * reference with another amount is a conflict.
   *
   * @param reference the till's own reference for this payment, or null to have one made
   * @param moneyAccountId the account the money will land in, or null for the shop's default card account
   */
  public Row request(UUID actor, long amountPaise, String reference, UUID moneyAccountId) {
    if (amountPaise <= 0 || amountPaise > MAX_PAISE) throw ApiException.validation("amountPaise", "must be between 1 and " + MAX_PAISE);
    String ref = reference == null || reference.isBlank() ? "TP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : reference.strip();
    if (ref.length() > 60) throw ApiException.validation("reference", "at most 60 characters");
    var existing = repo.findByReference(ref);
    if (existing.isPresent()) {
      if (existing.get().amountPaise() != amountPaise) {
        throw ApiException.conflict("REFERENCE_REUSED", "This reference was used for another amount", Map.of("fields", Map.of("reference", "already used")));
      }
      return existing.get();
    }
    UUID account = accounts.counterAccount("CARD", moneyAccountId, "moneyAccountId", false);
    CardTerminal t = terminal();
    Session s = t.requestPayment(amountPaise, ref);
    Row row = new Row(UUID.randomUUID(), ref, t.provider(), amountPaise, s.state().name(), s.mode().name(), s.instructions(), s.qrPayload(), s.qrImageUrl(),
        s.approvalCode(), s.rrn(), account, clock.instant());
    if (!repo.insert(row, actor, clock.instant())) return repo.findByReference(ref).orElseThrow(); // a parallel retry won
    audit.log(actor, "TERMINAL_PAYMENT", "terminal_payment", row.id(), ref + " amountPaise=" + amountPaise);
    return row;
  }

  /** The session as it stands; a pending one is refreshed from the terminal when the adapter reports results itself. */
  public Row get(UUID id) {
    Row r = repo.find(id).orElseThrow(() -> ApiException.notFound("Terminal payment"));
    if (!"PENDING".equals(r.state())) return r;
    CardTerminal t = registry.byProvider(r.provider());
    if (t.manualConfirmation()) return r;
    Session s = t.status(r.reference());
    if (s.state() != CardTerminal.State.PENDING) repo.closePending(id, s.state().name(), s.approvalCode(), s.rrn(), clock.instant());
    return repo.find(id).orElseThrow();
  }

  /**
   * The cashier says the machine approved: stores the approval code and RRN. Only for adapters that cannot report the result themselves.
   * Confirming an approved payment again returns it unchanged.
   *
   * @throws ApiException 400 when the code is blank; 409 {@code TERMINAL_PAYMENT_CLOSED} when it was cancelled or declined
   */
  public Row confirm(UUID actor, UUID id, String approvalCode, String rrn) {
    String code = approvalCode == null ? "" : approvalCode.strip();
    if (code.isEmpty() || code.length() > 40) throw ApiException.validation("approvalCode", "1 to 40 characters");
    if (rrn != null && rrn.strip().length() > 40) throw ApiException.validation("rrn", "at most 40 characters");
    Row r = repo.find(id).orElseThrow(() -> ApiException.notFound("Terminal payment"));
    if (!registry.byProvider(r.provider()).manualConfirmation()) {
      throw ApiException.conflict("TERMINAL_CONFIRMS_ITSELF", "This terminal reports the result itself", Map.of());
    }
    if ("APPROVED".equals(r.state())) return r;
    if (!repo.closePending(id, "APPROVED", code, rrn == null || rrn.isBlank() ? null : rrn.strip(), clock.instant())) {
      throw ApiException.conflict("TERMINAL_PAYMENT_CLOSED", "This payment was already " + r.state().toLowerCase(), Map.of());
    }
    audit.log(actor, "TERMINAL_PAYMENT_CONFIRM", "terminal_payment", id, r.reference());
    return repo.find(id).orElseThrow();
  }

  /** Cancels a pending session; a session that is already over is returned as it is. */
  public Row cancel(UUID actor, UUID id) {
    Row r = repo.find(id).orElseThrow(() -> ApiException.notFound("Terminal payment"));
    if (!"PENDING".equals(r.state())) return r;
    Session s = registry.byProvider(r.provider()).cancel(r.reference());
    repo.closePending(id, s.state() == CardTerminal.State.PENDING ? "CANCELLED" : s.state().name(), null, null, clock.instant());
    audit.log(actor, "TERMINAL_PAYMENT_CANCEL", "terminal_payment", id, r.reference());
    return repo.find(id).orElseThrow();
  }
}
