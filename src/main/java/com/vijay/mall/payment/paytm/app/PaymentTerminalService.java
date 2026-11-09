package com.vijay.mall.payment.paytm.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.app.MoneyAccountService;
import com.vijay.mall.payment.infra.MoneyAccountRepository.Account;
import com.vijay.mall.payment.paytm.domain.PaytmTerminalService;
import com.vijay.mall.payment.paytm.domain.PaytmTerminalService.ConnectionResult;
import com.vijay.mall.payment.paytm.infra.PaymentTerminalEntity;
import com.vijay.mall.payment.paytm.infra.PaymentTerminalJpa;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The card machines master: the admin adds, renames and switches machines off; the till reads the active ones. */
@Service
public class PaymentTerminalService {
  /** The vendor name stored in the table. */
  public static final String PROVIDER = "paytm";
  private static final Pattern TID = Pattern.compile("[A-Za-z0-9]{8}");
  private static final int MAX_NAME = 60;

  /**
   * A machine as the admin sees it.
   * @param id our id
   * @param provider the vendor
   * @param terminalId the vendor's terminal id
   * @param name the name shown at the till
   * @param active whether the till may use it
   * @param moneyAccountId the machine's own CARD_TERMINAL money account in the bank module (made with the machine, no foreign key)
   * @param settlesToAccountId the BANK account that account pays out into, or null
   * @param createdAt when it was added
   */
  public record TerminalView(UUID id, String provider, String terminalId, String name, boolean active, UUID moneyAccountId, UUID settlesToAccountId,
                             Instant createdAt) {}

  /**
   * A machine as the till sees it.
   * @param id our id, sent when starting a payment
   * @param provider the vendor, so the till can show it next to other payment channels
   * @param name the name shown at the till
   * @param terminalId the vendor's terminal id
   * @param moneyAccountId the machine's money account, which is the {@code moneyAccountId} of its entry in the payment channels
   * @param isDefault true when this is the only active machine, so the till picks it without asking
   */
  public record PosTerminalView(UUID id, String provider, String name, String terminalId, UUID moneyAccountId, @JsonProperty("default") boolean isDefault) {}

  /**
   * Fields of a new or changed machine.
   * @param terminalId the vendor terminal id, 8 letters or digits (ignored on change: it cannot be changed)
   * @param name the name shown at the till
   * @param active whether the till may use it (a new machine is active when omitted)
   * @param settlesToAccountId the BANK money account the machine pays out into, or null
   */
  public record TerminalInput(String terminalId, String name, Boolean active, UUID settlesToAccountId) {}

  private final PaymentTerminalJpa terminals;
  private final PaytmTerminalService paytm;
  private final MoneyAccountService accounts;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public PaymentTerminalService(PaymentTerminalJpa terminals, PaytmTerminalService paytm, MoneyAccountService accounts, AuditService audit, Clock clock) {
    this.terminals = terminals;
    this.paytm = paytm;
    this.accounts = accounts;
    this.audit = audit;
    this.clock = clock;
  }

  private TerminalView view(PaymentTerminalEntity e, Map<UUID, Account> accountsById) {
    Account a = e.moneyAccountId == null ? null : accountsById.get(e.moneyAccountId);
    return new TerminalView(e.id, e.provider, e.terminalId, e.name, e.active, e.moneyAccountId, a == null ? null : a.settlesToAccountId(), e.createdAt);
  }

  private TerminalView view(PaymentTerminalEntity e) {
    return view(e, e.moneyAccountId == null ? Map.of() : accounts.byIds(List.of(e.moneyAccountId)));
  }

  /** One page of machines, newest first. */
  @Transactional(readOnly = true)
  public Page<TerminalView> list(int page, int size) {
    int s = Page.clampSize(size);
    int p = Math.max(0, page);
    var r = terminals.findAllByOrderByCreatedAtDesc(PageRequest.of(p, s));
    Map<UUID, Account> byId = accounts.byIds(r.getContent().stream().map(e -> e.moneyAccountId).filter(Objects::nonNull).toList());
    return new Page<>(r.getContent().stream().map(e -> view(e, byId)).toList(), p, s, r.getTotalElements());
  }

  /** The active machines for the till. */
  @Transactional(readOnly = true)
  public List<PosTerminalView> activeForTill() {
    List<PaymentTerminalEntity> all = terminals.findByProviderAndActiveTrueOrderByNameAsc(PROVIDER);
    return all.stream().map(e -> new PosTerminalView(e.id, e.provider, e.name, e.terminalId, e.moneyAccountId, all.size() == 1)).toList();
  }

  private static String checkName(String name) {
    String n = name == null ? "" : name.strip();
    if (n.isEmpty() || n.length() > MAX_NAME) throw ApiException.validation("name", "name is required (at most " + MAX_NAME + " characters)");
    return n;
  }

  /** Adds a machine. 409 {@code DUPLICATE_TERMINAL} when the terminal id is already there. */
  @Transactional
  public TerminalView create(UUID actor, TerminalInput in) {
    String tid = in.terminalId() == null ? "" : in.terminalId().strip();
    if (!TID.matcher(tid).matches()) throw ApiException.validation("terminalId", "terminal id must be 8 letters or digits");
    String name = checkName(in.name());
    if (terminals.existsByProviderAndTerminalId(PROVIDER, tid)) throw duplicate(tid);
    Instant now = clock.instant();
    PaymentTerminalEntity e = PaymentTerminalEntity.create();
    e.id = UUID.randomUUID();
    e.provider = PROVIDER;
    e.terminalId = tid;
    e.name = name;
    e.active = in.active() == null || in.active();
    e.moneyAccountId = accounts.ensureTerminalAccount(actor, PROVIDER, tid, name, in.settlesToAccountId(), e.active).id();
    e.createdAt = now;
    e.updatedAt = now;
    try {
      terminals.saveAndFlush(e);
    } catch (DataIntegrityViolationException race) {
      throw duplicate(tid);
    }
    audit.log(actor, "TERMINAL_CREATE", "payment_terminal", e.id, e.provider + " " + tid + " " + name);
    return view(e);
  }

  private static ApiException duplicate(String tid) {
    return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_TERMINAL", "This terminal id is already added",
        Map.of("fields", Map.of("terminalId", "terminal " + tid + " already exists")));
  }

  private UUID currentSettlesTo(PaymentTerminalEntity e) {
    Account a = e.moneyAccountId == null ? null : accounts.byIds(List.of(e.moneyAccountId)).get(e.moneyAccountId);
    return a == null ? null : a.settlesToAccountId();
  }

  private PaymentTerminalEntity find(UUID id) {
    return terminals.findById(id).orElseThrow(() -> ApiException.notFound("Terminal"));
  }

  /** Renames a machine, switches it on or off and sets the bank its money account pays out into; the machine's account follows each change. */
  @Transactional
  public TerminalView update(UUID actor, UUID id, TerminalInput in) {
    PaymentTerminalEntity e = find(id);
    e.name = checkName(in.name());
    if (in.active() != null) e.active = in.active();
    e.moneyAccountId = accounts.ensureTerminalAccount(actor, PROVIDER, e.terminalId, e.name, in.settlesToAccountId(), e.active).id();
    e.updatedAt = clock.instant();
    terminals.save(e);
    audit.log(actor, "TERMINAL_UPDATE", "payment_terminal", id, e.terminalId + " name=" + e.name + " active=" + e.active);
    return view(e);
  }

  /** Switches a machine and its money account off. Its past payments stay. */
  @Transactional
  public TerminalView deactivate(UUID actor, UUID id) {
    PaymentTerminalEntity e = find(id);
    e.active = false;
    Account a = accounts.ensureTerminalAccount(actor, PROVIDER, e.terminalId, e.name, currentSettlesTo(e), false);
    e.moneyAccountId = a.id();
    e.updatedAt = clock.instant();
    terminals.save(e);
    audit.log(actor, "TERMINAL_DEACTIVATE", "payment_terminal", id, e.terminalId);
    return view(e);
  }

  /** Checks signature, merchant id and terminal with a status call for a made-up payment. Moves no money. */
  public ConnectionResult test(UUID actor, UUID id) {
    PaymentTerminalEntity e = terminals.findById(id).orElseThrow(() -> ApiException.notFound("Terminal"));
    ConnectionResult r = paytm.testConnection(e.terminalId);
    audit.log(actor, "TERMINAL_TEST", "payment_terminal", id, e.terminalId + " -> " + r.code());
    return r;
  }
}
