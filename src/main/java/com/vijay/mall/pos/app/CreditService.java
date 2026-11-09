package com.vijay.mall.pos.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.identity.domain.MobileNumbers;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.domain.CreditMath;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.domain.Money;
import com.vijay.mall.pos.domain.ReceiptData;
import com.vijay.mall.pos.domain.ReceiptText;
import com.vijay.mall.pos.domain.RepaymentData;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.infra.CreditRepository;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
  /** Longest note accepted on a repayment or adjustment, in characters. */
  static final int MAX_NOTE = 200;

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

  /**
   * limitPaise 0 means credit is not enabled for this customer.
   * @param blocked whether the customer's account is blocked; a blocked customer cannot be given credit whatever the limit
   */
  public record AccountView(UUID customerId, String name, String mobile, long balancePaise, long limitPaise,
                            long availablePaise, boolean blocked) {}

  /**
   * A customer in the till's credit picker. The mobile number is masked ({@code 98XXXXXX10}); the full number is only given by the
   * account lookup of one customer.
   * @param blocked whether the customer is blocked and cannot be given credit
   */
  @Schema(name = "CreditCustomer")
  public record PickView(UUID customerId, String name, String maskedMobile, long limitPaise, long balancePaise,
                         long availablePaise, boolean blocked) {}

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

  private LiveEvents live;

  /** Optional: tells open app screens about changes (not provided in plain unit tests). */
  @Autowired(required = false)
  void setLive(LiveEvents live) { this.live = live; }

  private void changed(UUID customerId, String reason, long delta) { if (live != null) live.credit(customerId, reason, delta); }

  /** Creates the service. */
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
  /**
   * The credit account of a customer: balance owed, limit and what is still available, all in paise.
   *
   * @throws ApiException 404 if the customer does not exist
   */
  public AccountView account(UUID customerId) {
    User u = users.findById(customerId).orElseThrow(() -> ApiException.notFound("Customer"));
    return view(u, credit.find(customerId));
  }

  /** For the till: look a customer up by mobile before offering credit. */
  public AccountView accountByMobile(String rawMobile) {
    User u = users.findByMobile(mobileOf(rawMobile)).orElseThrow(() -> ApiException.notFound("Customer"));
    return view(u, credit.find(u.id()));
  }

  /**
   * One customer's credit ledger, newest first.
   *
   * @param page zero-based page number (negative values count as 0)
   * @param size page size (clamped to the allowed maximum)
   */
  public Page<LedgerView> ledger(UUID customerId, int page, int size) {
    Page<CreditRepository.Entry> p = credit.ledger(customerId, Math.max(0, page), Page.clampSize(size));
    return new Page<>(p.items().stream().map(e -> new LedgerView(e.id(), e.kind(), e.deltaPaise(), e.balanceAfterPaise(),
        e.refNo(), e.note(), e.at())).toList(), p.page(), p.size(), p.total());
  }

  /**
   * For the till: customers who may buy on credit (a limit above zero), by name, so the cashier can find one without typing the
   * whole number. Read only: switching credit on or changing a limit stays with the admin.
   *
   * @param q part of the name or mobile number, any case; at least 2 characters when {@code everyone} is set
   * @param everyone true to list customers without a credit limit too
   * @param page zero-based page number (negative values count as 0)
   * @param size page size (clamped to the allowed maximum)
   * @throws ApiException 400 when {@code q} is longer than 100 characters, or too short for {@code everyone}
   */
  public Page<PickView> pick(String q, boolean everyone, int page, int size) {
    String text = q == null ? "" : q.trim().toLowerCase().replace("%", "").replace("_", "");
    if (q != null && q.length() > 100) throw ApiException.validation("q", "must be at most 100 characters");
    if (everyone && text.length() < 2) throw ApiException.validation("q", "enter at least 2 characters to search all customers");
    Page<CreditRepository.Pick> p = credit.pick(text.isEmpty() ? null : "%" + text + "%", everyone, Math.max(0, page), Page.clampSize(size));
    return p.withItems(p.items().stream().map(c -> new PickView(c.userId(), c.name(), mask(c.mobile()), c.limitPaise(), c.balancePaise(),
        CreditMath.available(c.limitPaise(), c.balancePaise()), c.blocked())).toList());
  }

  /** {@code 9876543210} becomes {@code 98XXXXXX10}; a missing number stays empty and a short one shows nothing useful. */
  static String mask(String mobile) {
    if (mobile == null) return null;
    if (mobile.length() < 5) return "XXXX";
    return mobile.substring(0, 2) + "X".repeat(mobile.length() - 4) + mobile.substring(mobile.length() - 2);
  }

  private AccountView view(User u, CreditRepository.Account a) {
    return new AccountView(u.id(), u.name(), u.mobile(), a.balancePaise(), a.limitPaise(),
        CreditMath.available(a.limitPaise(), a.balancePaise()), users.isBlocked(u.id()));
  }

  private static String mobileOf(String raw) {
    return MobileNumbers.normalize(raw).orElseThrow(() -> ApiException.validation("mobile", "enter a 10 digit mobile number"));
  }

  // ---------- credit on a bill ----------
  /**
   * Puts part or all of a bill on the customer's account.
   *
   * @throws ApiException 422 {@code CREDIT_NOT_ALLOWED} when the customer has no credit limit, {@code CUSTOMER_BLOCKED} when the
   *     customer is blocked, {@code CREDIT_LIMIT_EXCEEDED} when the limit would be passed
   */
  public void chargeSale(UUID customerId, long amountPaise, UUID saleId, String invoiceNo, UUID cashierId, Instant now) {
    CreditRepository.Account a = credit.lock(customerId, now);
    if (a.limitPaise() <= 0) {
      throw ApiException.unprocessable("CREDIT_NOT_ALLOWED", "Credit is not enabled for this customer",
          Map.of("customerId", customerId.toString()));
    }
    if (users.isBlocked(customerId)) {
      throw ApiException.unprocessable("CUSTOMER_BLOCKED", "This customer is blocked, so credit cannot be given",
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

  /**
   * Writes the ledger entry for goods returned from a bill bought on credit, which lowers what the customer owes.
   *
   * @param amountPaise how much of the refund cancelled credit (positive; stored as a negative entry)
   * @param creditNoteNo credit note number shown as the entry reference
   */
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
    List<CreditRepository.Line> parts = PayModes.normalize(payments, false, "payments").stream().map(p -> {
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

  /**
   * The receipt of a repayment as plain text for a thermal printer: customer, amount, payment modes, the balance before and
   * after and the receipt number.
   *
   * @param id the repayment
   * @param width characters per line, 32 or 48
   * @throws ApiException 404 for an unknown repayment, 400 for a bad width
   */
  public String receiptText(UUID id, int width) {
    CreditRepository.Repayment r = credit.findRepayment(id).orElseThrow(() -> ApiException.notFound("Payment"));
    User customer = users.findById(r.userId()).orElseThrow(() -> ApiException.notFound("Customer"));
    String cashier = users.findById(r.cashierId()).map(u -> u.name() != null && !u.name().isBlank() ? u.name() : u.email()).orElse("");
    // the ledger line holds the balance right after this payment; without it fall back to today's balance
    long after = credit.entryOfPayment(id).map(CreditRepository.Entry::balanceAfterPaise).orElseGet(() -> credit.find(r.userId()).balancePaise());
    ShopSetting s = shop.get();
    List<ReceiptData.PaymentLine> lines = r.lines().stream().map(l -> new ReceiptData.PaymentLine(l.mode(), l.amountPaise())).toList();
    RepaymentData data = new RepaymentData(s.shopName(), s.address(), s.phone(), s.gstin(), r.receiptNo(),
        STAMP.format(r.at().atZone(InvoiceNumbers.SHOP_ZONE)), cashier, customer.mobile(), r.amountPaise(), lines,
        after + r.amountPaise(), after, r.note(), s.receiptFooter());
    try {
      return ReceiptText.renderRepayment(data, width);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("width", e.getMessage());
    }
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
