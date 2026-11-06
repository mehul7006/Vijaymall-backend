package com.vijay.mall.pos.app;

import io.swagger.v3.oas.annotations.media.Schema;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.MobileNumbers;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.domain.CreditMath;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.domain.Money;
import com.vijay.mall.pos.domain.PaymentRules;
import com.vijay.mall.pos.infra.CreditRepository;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credit (udhar) for customers at the shop counter: what each customer owes, how much they may owe, taking credit on a
 * bill, taking repayments, and undoing credit when goods come back. Never used for online orders.
 */
@Service
public class CreditService {
  static final int MAX_NOTE = 200;

  /** limitPaise 0 means credit is not enabled for this customer. */
  public record AccountView(UUID customerId, String name, String mobile, long balancePaise, long limitPaise,
                            long availablePaise) {}

  /** deltaPaise above 0 means the customer owes more; below 0 means they owe less. */
  @Schema(name = "CreditLedgerEntry")
  public record LedgerView(UUID id, String kind, long deltaPaise, long balanceAfterPaise, String refNo, String note,
                           Instant at) {}

  /** balanceDuePaise is what the customer owes now. */
  @Schema(name = "CreditReceipt")
  public record Receipt(UUID id, String receiptNo, UUID customerId, String customerMobile, long amountPaise,
                        List<Pay> payments, long balanceDuePaise, String note, Instant at) {}

  private final CreditRepository credit;
  private final UserRepository users;
  private final InvoiceCounterRepository counters;
  private final ShopService shop;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  private com.vijay.mall.common.live.LiveEvents live;

  /** Optional: tells open app screens about changes (not provided in plain unit tests). */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLive(com.vijay.mall.common.live.LiveEvents live) { this.live = live; }

  private void changed(UUID customerId, String reason, long delta) { if (live != null) live.credit(customerId, reason, delta); }

  public CreditService(CreditRepository credit, UserRepository users, InvoiceCounterRepository counters, ShopService shop,
                       AuditService audit, TransactionTemplate tx, Clock clock) {
    this.credit = credit;
    this.users = users;
    this.counters = counters;
    this.shop = shop;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  // ---------- read ----------
  public AccountView account(UUID customerId) {
    User u = users.findById(customerId).orElseThrow(() -> ApiException.notFound("Customer"));
    return view(u, credit.find(customerId));
  }

  /** For the till: look a customer up by mobile before offering credit. */
  public AccountView accountByMobile(String rawMobile) {
    User u = users.findByMobile(mobileOf(rawMobile)).orElseThrow(() -> ApiException.notFound("Customer"));
    return view(u, credit.find(u.id()));
  }

  public Page<LedgerView> ledger(UUID customerId, int page, int size) {
    Page<CreditRepository.Entry> p = credit.ledger(customerId, Math.max(0, page), Page.clampSize(size));
    return new Page<>(p.items().stream().map(e -> new LedgerView(e.id(), e.kind(), e.deltaPaise(), e.balanceAfterPaise(),
        e.refNo(), e.note(), e.at())).toList(), p.page(), p.size(), p.total());
  }

  private static AccountView view(User u, CreditRepository.Account a) {
    return new AccountView(u.id(), u.name(), u.mobile(), a.balancePaise(), a.limitPaise(),
        CreditMath.available(a.limitPaise(), a.balancePaise()));
  }

  private static String mobileOf(String raw) {
    return MobileNumbers.normalize(raw).orElseThrow(() -> ApiException.validation("mobile", "enter a 10 digit mobile number"));
  }

  // ---------- credit on a bill ----------
  /** Puts part or all of a bill on the customer's account. Fails when credit is off or the limit would be passed. */
  public void chargeSale(UUID customerId, long amountPaise, UUID saleId, String invoiceNo, UUID cashierId, Instant now) {
    CreditRepository.Account a = credit.lock(customerId, now);
    if (a.limitPaise() <= 0 || users.isBlocked(customerId)) {
      throw ApiException.unprocessable("CREDIT_NOT_ALLOWED", "Credit is not enabled for this customer",
          Map.of("customerId", customerId.toString()));
    }
    if (!CreditMath.fits(a.limitPaise(), a.balancePaise(), amountPaise)) {
      throw ApiException.unprocessable("CREDIT_LIMIT_EXCEEDED", "More than the customer's available credit of Rs "
          + Money.plain(CreditMath.available(a.limitPaise(), a.balancePaise())),
          Map.of("limitPaise", a.limitPaise(), "balancePaise", a.balancePaise(),
              "availablePaise", CreditMath.available(a.limitPaise(), a.balancePaise()), "requestedPaise", amountPaise));
    }
    credit.append(customerId, "SALE", amountPaise, invoiceNo, saleId, null, null, "", cashierId, now);
    changed(customerId, "SALE", amountPaise);
  }

  // ---------- goods coming back ----------
  /** How much of a refund just cancels what the customer owes for this bill (see CreditMath.returnAdjustment). */
  public long returnAdjustment(UUID customerId, UUID saleId, long creditOnBill, long refundPaise, Instant now) {
    CreditRepository.Account a = credit.lock(customerId, now);
    return CreditMath.returnAdjustment(creditOnBill, credit.returnedAgainst(saleId), refundPaise, a.balancePaise());
  }

  public void recordReturn(UUID customerId, UUID saleId, UUID returnId, String creditNoteNo, long amountPaise,
                           UUID cashierId, Instant now) {
    credit.append(customerId, "RETURN", -amountPaise, creditNoteNo, saleId, returnId, null, "", cashierId, now);
    changed(customerId, "RETURN", -amountPaise);
  }

  // ---------- repayment ----------
  /** The customer pays some or all of what they owe. Once per idempotency key; a repeat returns the first receipt. */
  public Receipt collect(UUID cashierId, String idemKey, String rawMobile, List<Pay> payments, String note) {
    if (idemKey == null || idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
    Optional<CreditRepository.Repayment> existing = credit.findRepaymentByIdem(idemKey);
    if (existing.isPresent()) return receipt(existing.get());
    try {
      UUID id = tx.execute(s -> doCollect(cashierId, idemKey, rawMobile, payments, note));
      return receipt(credit.findRepayment(id).orElseThrow());
    } catch (DataIntegrityViolationException e) {
      return receipt(credit.findRepaymentByIdem(idemKey).orElseThrow(() -> e));
    }
  }

  private UUID doCollect(UUID cashierId, String idemKey, String rawMobile, List<Pay> payments, String note) {
    User u = users.findByMobile(mobileOf(rawMobile)).orElseThrow(() -> ApiException.notFound("Customer"));
    if (payments == null || payments.isEmpty()) throw ApiException.validation("payments", "enter how the customer is paying");
    List<CreditRepository.Line> parts = payments.stream().map(p -> {
      if (!PaymentRules.MODES.contains(p.mode())) throw ApiException.validation("payments", "payment mode must be CASH, UPI or CARD");
      if (p.amountPaise() <= 0) throw ApiException.validation("payments", "payment amounts must be positive");
      return new CreditRepository.Line(p.mode(), p.amountPaise(), p.reference());
    }).toList();
    long total = parts.stream().mapToLong(CreditRepository.Line::amountPaise).sum();
    String text = noteOf(note);

    Instant now = clock.instant();
    CreditRepository.Account a = credit.lock(u.id(), now);
    if (total > a.balancePaise()) {
      throw ApiException.unprocessable("CREDIT_OVERPAYMENT", "The customer owes only Rs " + Money.plain(a.balancePaise()),
          Map.of("balancePaise", a.balancePaise(), "paidPaise", total));
    }
    String fy = InvoiceNumbers.financialYear(now);
    String receiptNo = InvoiceNumbers.format(shop.get().invoicePrefix() + "-CR", fy, counters.next("CREDIT_RECEIPT", fy));
    UUID id = credit.insertRepayment(UUID.randomUUID(), receiptNo, idemKey, u.id(), cashierId, total, text, now, parts);
    credit.append(u.id(), "PAYMENT", -total, receiptNo, null, null, id, text, cashierId, now);
    changed(u.id(), "PAYMENT", -total);
    audit.log(cashierId, "CREDIT_PAYMENT", "credit_payment", id, receiptNo + " amount=" + total);
    return id;
  }

  private Receipt receipt(CreditRepository.Repayment r) {
    User u = users.findById(r.userId()).orElseThrow(() -> ApiException.notFound("Customer"));
    List<Pay> pays = r.lines().stream().map(l -> new Pay(l.mode(), l.amountPaise(), l.reference())).toList();
    return new Receipt(r.id(), r.receiptNo(), r.userId(), u.mobile(), r.amountPaise(), pays,
        credit.find(r.userId()).balancePaise(), r.note(), r.at());
  }

  // ---------- admin ----------
  /** Sets how much the customer may owe. 0 switches credit off for them. */
  @Transactional
  public AccountView setLimit(UUID customerId, long limitPaise, UUID actor) {
    if (limitPaise < 0) throw ApiException.validation("limitPaise", "must not be negative");
    users.findById(customerId).orElseThrow(() -> ApiException.notFound("Customer"));
    credit.setLimit(customerId, limitPaise, clock.instant());
    changed(customerId, "LIMIT", 0);
    audit.log(actor, "CREDIT_LIMIT", "credit_account", customerId, "limit=" + limitPaise);
    return account(customerId);
  }

  /**
   * Approves credit for a customer by mobile number. A customer the shop has never seen is created, so credit can be
   * approved before their first bill (a bill on credit for an unapproved number is refused).
   */
  @Transactional
  public AccountView setLimitByMobile(String rawMobile, long limitPaise, UUID actor) {
    String mobile = mobileOf(rawMobile);
    if (limitPaise < 0) throw ApiException.validation("limitPaise", "must not be negative");
    User u = users.findByMobile(mobile).orElseGet(() -> users.insertCounterCustomer(mobile, clock.instant()));
    return setLimit(u.id(), limitPaise, actor);
  }

  /** A correction or write-off: negative lowers what the customer owes, positive raises it. A reason is required. */
  @Transactional
  public AccountView adjust(UUID customerId, long deltaPaise, String note, UUID actor) {
    if (deltaPaise == 0) throw ApiException.validation("amountPaise", "must not be zero");
    if (note == null || note.isBlank()) throw ApiException.validation("note", "a reason is required");
    users.findById(customerId).orElseThrow(() -> ApiException.notFound("Customer"));
    Instant now = clock.instant();
    CreditRepository.Account a = credit.lock(customerId, now);
    if (a.balancePaise() + deltaPaise < 0) {
      throw ApiException.unprocessable("CREDIT_ADJUST_TOO_LARGE", "More than the customer owes (Rs " + Money.plain(a.balancePaise()) + ")",
          Map.of("balancePaise", a.balancePaise()));
    }
    String text = noteOf(note);
    credit.append(customerId, "ADJUST", deltaPaise, null, null, null, null, text, actor, now);
    changed(customerId, "ADJUST", deltaPaise);
    audit.log(actor, "CREDIT_ADJUST", "credit_account", customerId, "delta=" + deltaPaise + " " + text);
    return account(customerId);
  }

  private static String noteOf(String note) {
    String t = note == null ? "" : note.strip();
    if (t.length() > MAX_NOTE) throw ApiException.validation("note", "must be at most " + MAX_NOTE + " characters");
    return t;
  }
}
