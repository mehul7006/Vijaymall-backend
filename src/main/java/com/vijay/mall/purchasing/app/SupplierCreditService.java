package com.vijay.mall.purchasing.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.purchasing.app.PurchaseService.PurchaseView;
import com.vijay.mall.purchasing.domain.Rounding;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import com.vijay.mall.purchasing.infra.SupplierLedgerRepository;
import com.vijay.mall.purchasing.infra.SupplierLedgerRepository.Due;
import com.vijay.mall.purchasing.infra.SupplierLedgerRepository.Note;
import com.vijay.mall.purchasing.infra.SupplierLedgerRepository.Refund;
import com.vijay.mall.purchasing.infra.SupplierRepository;
import com.vijay.mall.purchasing.infra.SupplierRepository.Supplier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The supplier's account: the statement, and the two ways to use a supplier's credit. Credit is the negative amount due of a purchase (a debit
 * note on a purchase that was already paid). It can be used to pay another purchase of the same supplier (payment mode CREDIT_NOTE with the
 * debit note that gave the credit) or be paid back by the supplier in cash or by bank (a refund). Both take the credit out of the purchase that
 * had it, so the supplier's balance (the sum of the due amounts) stays equal to the last line of the statement.
 *
 * <p>Every use first locks the supplier row, so two requests for one supplier run one after the other and can never use the same credit twice;
 * the credit itself is taken with a conditional update as well.
 */
@Service
public class SupplierCreditService {
  /** Payment mode of a payment made with the credit of a debit note. */
  public static final String CREDIT_NOTE = "CREDIT_NOTE";

  /** A refund received from a supplier. {@code receivedAt} defaults to now; {@code moneyAccountId} is stored but not validated yet. */
  @Schema(name = "SupplierRefundInput")
  public record RefundInput(@Min(1) long amountPaise, @NotNull @Size(max = 20) String mode, @Size(max = 80) String reference, Instant receivedAt,
                            UUID moneyAccountId) {}

  /** A stored refund with what the supplier has left in credit and its balance after it. */
  @Schema(name = "SupplierRefundView")
  public record RefundView(UUID id, UUID supplierId, long amountPaise, String mode, String reference, UUID moneyAccountId, Instant receivedAt,
                           UUID createdBy, Instant createdAt, long creditPaise, long balancePaise) {}

  /**
   * One line of the statement. {@code kind} is PURCHASE, PAYMENT, DEBIT_NOTE, SUPPLIER_REFUND or CREDIT_USED (the plus side of a payment made
   * with credit). {@code amountPaise} is signed: plus means the shop owes more. {@code refId} is the id of the purchase, payment, debit note or
   * refund; {@code purchaseId} and {@code invoiceNo} name the purchase the line belongs to (empty for a refund).
   */
  @Schema(name = "SupplierStatementRow")
  public record StatementRow(LocalDate date, Instant at, String kind, UUID refId, UUID purchaseId, String invoiceNo, String mode, String reference,
                             long amountPaise, long balancePaise) {}

  /**
   * A supplier's statement for a period: what was owed at the start, the lines (one page) with the running balance, and what is owed at the end.
   * Without {@code to} the closing balance is the current one and equals the supplier's {@code balancePaise}.
   */
  @Schema(name = "SupplierStatement")
  public record Statement(UUID supplierId, String supplierName, LocalDate from, LocalDate to, long openingBalancePaise, long closingBalancePaise,
                          Page<StatementRow> rows) {}

  /**
   * A debit note a supplier gave. {@code date} is the India date it was made on. {@code availablePaise} is what it can still pay with the
   * credit-use API (payment mode CREDIT_NOTE): never more than the credit its purchase still holds.
   */
  @Schema(name = "SupplierCreditNoteView")
  public record CreditNoteView(UUID id, String number, LocalDate date, Instant createdAt, UUID purchaseId, String invoiceNo, long amountPaise,
                               long usedPaise, long availablePaise) {}

  /** Most refunds listed for a supplier. */
  public static final int MAX_REFUNDS = 50;

  private final SupplierLedgerRepository repo;
  private final SupplierRepository suppliers;
  private final PurchaseRepository purchaseRepo;
  private final PurchaseService purchases;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final AdminOps ops;

  /** Creates the service. */
  public SupplierCreditService(SupplierLedgerRepository repo, SupplierRepository suppliers, PurchaseRepository purchaseRepo, PurchaseService purchases,
                               AuditService audit, TransactionTemplate tx, Clock clock, AdminOps ops) {
    this.repo = repo;
    this.suppliers = suppliers;
    this.purchaseRepo = purchaseRepo;
    this.purchases = purchases;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
    this.ops = ops;
  }

  private static String text(String v) { return v == null ? "" : v.strip(); }

  /**
   * The supplier's statement from {@code from} to {@code to} (India dates, both optional and inclusive), {@code size} lines per page (1 to 50).
   *
   * @throws ApiException 404 unknown supplier; 400 when {@code from} is after {@code to}
   */
  public Statement statement(UUID supplierId, LocalDate from, LocalDate to, int page, int size) {
    Supplier s = suppliers.find(supplierId).orElseThrow(() -> ApiException.notFound("Supplier"));
    if (from != null && to != null && from.isAfter(to)) throw ApiException.validation("from", "must not be after to");
    var totals = repo.totals(supplierId, from, to);
    Page<SupplierLedgerRepository.Row> p = repo.statement(supplierId, from, to, totals.openingPaise(), Math.max(0, page), Page.clampSize(size));
    List<StatementRow> rows = p.items().stream().map(r -> new StatementRow(r.date(), r.at(), r.kind(), r.refId(), r.purchaseId(), r.invoiceNo(),
        r.mode(), r.reference(), r.amountPaise(), r.balancePaise())).toList();
    return new Statement(supplierId, s.name(), from, to, totals.openingPaise(), totals.openingPaise() + totals.inRangePaise(), p.withItems(rows));
  }

  /**
   * A page (size 1 to 50) of the supplier's debit notes, newest first, each with what was used and what is still available as credit.
   *
   * @throws ApiException 404 unknown supplier
   */
  public Page<CreditNoteView> creditNotes(UUID supplierId, int page, int size) {
    suppliers.find(supplierId).orElseThrow(() -> ApiException.notFound("Supplier"));
    Page<SupplierLedgerRepository.NoteRow> p = repo.notes(supplierId, Math.max(0, page), Page.clampSize(size));
    return p.withItems(p.items().stream().map(n -> new CreditNoteView(n.id(), n.noteNo(), n.createdAt().atZone(InvoiceNumbers.SHOP_ZONE).toLocalDate(),
        n.createdAt(), n.purchaseId(), n.invoiceNo(), n.totalPaise(), n.creditUsedPaise(),
        SupplierLedgerRepository.available(n.totalPaise(), n.creditUsedPaise(), n.purchaseDuePaise()))).toList());
  }

  /** The latest refunds of a supplier (at most {@link #MAX_REFUNDS}), newest first. */
  public List<RefundView> refunds(UUID supplierId) {
    suppliers.find(supplierId).orElseThrow(() -> ApiException.notFound("Supplier"));
    long credit = repo.credit(supplierId);
    long balance = repo.balance(supplierId);
    return repo.refunds(supplierId, MAX_REFUNDS).stream().map(f -> view(f, credit, balance)).toList();
  }

  private static RefundView view(Refund f, long credit, long balance) {
    return new RefundView(f.id(), f.supplierId(), f.amountPaise(), f.mode(), f.reference(), f.moneyAccountId(), f.receivedAt(), f.createdBy(),
        f.createdAt(), credit, balance);
  }

  private RefundView viewOf(UUID id) {
    Refund f = repo.findRefund(id).orElseThrow(() -> ApiException.notFound("Refund"));
    return view(f, repo.credit(f.supplierId()), repo.balance(f.supplierId()));
  }

  /**
   * Records money the supplier paid back, taken from its credit (the oldest credit first). Needs an Idempotency-Key.
   *
   * @throws ApiException 404 unknown supplier; 400 for a bad mode or a time in the future; 422 {@code REFUND_EXCEEDS_CREDIT} above the credit
   */
  public RefundView refund(UUID actor, String idemKey, UUID supplierId, RefundInput in) {
    PurchaseService.requireKey(idemKey);
    return ops.once(idemKey, "supplier-refund", actor, AdminOps.fingerprint(supplierId, in), () -> tx.execute(s -> doRefund(actor, supplierId, in)),
        v -> v.id().toString(), ref -> viewOf(UUID.fromString(ref)));
  }

  private RefundView doRefund(UUID actor, UUID supplierId, RefundInput in) {
    String mode = text(in.mode()).toUpperCase(Locale.ROOT);
    if (CREDIT_NOTE.equals(mode)) throw ApiException.validation("mode", "CREDIT_NOTE is not allowed for refunds");
    if (!PurchaseService.MODES.contains(mode)) throw ApiException.validation("mode", "must be CASH, UPI, CARD, BANK or CHEQUE");
    Instant now = clock.instant();
    if (in.receivedAt() != null && in.receivedAt().isAfter(now.plus(10, ChronoUnit.MINUTES))) throw ApiException.validation("receivedAt", "cannot be in the future");
    if (!repo.lockSupplier(supplierId)) throw ApiException.notFound("Supplier");
    List<Due> credits = repo.lockCreditPurchases(supplierId);
    long credit = credits.stream().mapToLong(d -> -d.duePaise()).sum();
    if (in.amountPaise() > credit) {
      throw ApiException.unprocessable("REFUND_EXCEEDS_CREDIT", "The refund is more than the supplier's credit",
          Map.of("fields", Map.of("amountPaise", "at most " + credit), "creditPaise", credit));
    }
    long left = in.amountPaise();
    for (Due d : credits) {
      if (left == 0) break;
      long take = Math.min(left, -d.duePaise());
      useCredit(d.id(), take);
      left -= take;
    }
    Refund f = new Refund(UUID.randomUUID(), supplierId, in.amountPaise(), mode, text(in.reference()), in.moneyAccountId(),
        in.receivedAt() == null ? now : in.receivedAt(), actor, now);
    repo.insertRefund(f);
    audit.log(actor, "SUPPLIER_REFUND", "supplier", supplierId, mode + " " + in.amountPaise() + " " + f.reference());
    return view(f, repo.credit(supplierId), repo.balance(supplierId));
  }

  private void useCredit(UUID purchaseId, long amount) {
    if (!repo.useCredit(purchaseId, amount)) {
      throw ApiException.unprocessable("CREDIT_EXCEEDS_AVAILABLE", "The credit was already used", Map.of("fields", Map.of("amountPaise", "credit changed: try again")));
    }
  }

  /**
   * Pays a purchase with the credit of a debit note of the same supplier (payment mode CREDIT_NOTE). Needs an Idempotency-Key. Answers with the
   * paid purchase as it is now.
   *
   * @throws ApiException 404 unknown purchase; 400 {@code debitNoteId} missing, unknown, of another supplier or on the purchase being paid;
   *     409 {@code ALREADY_PAID} nothing is due; 422 {@code PAYMENT_EXCEEDS_DUE} above the due amount, {@code CREDIT_EXCEEDS_AVAILABLE}
   *     above the credit left on the debit note
   */
  public PurchaseView payWithCredit(UUID actor, String idemKey, UUID purchaseId, PurchaseService.PaymentInput in) {
    PurchaseService.requireKey(idemKey);
    return ops.once(idemKey, "purchase-credit-payment", actor, AdminOps.fingerprint(purchaseId, in), () -> tx.execute(s -> doCredit(actor, purchaseId, in)),
        v -> v.id().toString(), ref -> purchases.get(UUID.fromString(ref), Rounding.PAISE));
  }

  private PurchaseView doCredit(UUID actor, UUID purchaseId, PurchaseService.PaymentInput in) {
    if (in.debitNoteId() == null) throw ApiException.validation("debitNoteId", "required when the mode is CREDIT_NOTE");
    Instant now = clock.instant();
    if (in.paidAt() != null && in.paidAt().isAfter(now.plus(10, ChronoUnit.MINUTES))) throw ApiException.validation("paidAt", "cannot be in the future");
    UUID supplierId = repo.supplierOf(purchaseId).orElseThrow(() -> ApiException.notFound("Purchase"));
    repo.lockSupplier(supplierId);
    Note note = repo.lockNote(in.debitNoteId()).orElseThrow(() -> ApiException.validation("debitNoteId", "debit note does not exist"));
    if (!note.supplierId().equals(supplierId)) throw ApiException.validation("debitNoteId", "belongs to another supplier");
    if (note.purchaseId().equals(purchaseId)) throw ApiException.validation("debitNoteId", "the credit of a purchase cannot pay that same purchase");
    Map<UUID, Due> locked = repo.lockPurchases(List.of(purchaseId, note.purchaseId()));
    long due = locked.get(purchaseId).duePaise();
    if (due <= 0) throw ApiException.conflict("ALREADY_PAID", "Nothing is due on this purchase", Map.of("duePaise", due));
    if (in.amountPaise() > due) {
      throw ApiException.unprocessable("PAYMENT_EXCEEDS_DUE", "The payment is more than the amount due",
          Map.of("fields", Map.of("amountPaise", "at most " + due), "duePaise", due));
    }
    long available = SupplierLedgerRepository.available(note.totalPaise(), note.creditUsedPaise(), locked.get(note.purchaseId()).duePaise());
    if (in.amountPaise() > available) {
      throw ApiException.unprocessable("CREDIT_EXCEEDS_AVAILABLE", "The payment is more than the credit left on this debit note",
          Map.of("fields", Map.of("amountPaise", "at most " + available), "availablePaise", available));
    }
    useCredit(note.purchaseId(), in.amountPaise());
    repo.addNoteCreditUsed(note.id(), in.amountPaise());
    var payment = new PurchaseRepository.Payment(UUID.randomUUID(), purchaseId, in.amountPaise(), CREDIT_NOTE, note.noteNo(), text(in.note()), null,
        in.paidAt() == null ? now : in.paidAt(), actor, now);
    purchaseRepo.insertPayment(payment);
    purchaseRepo.addPaid(purchaseId, in.amountPaise());
    repo.linkNote(payment.id(), note.id());
    audit.log(actor, "PURCHASE_CREDIT_USE", "purchase", purchaseId, CREDIT_NOTE + " " + in.amountPaise() + " " + note.noteNo());
    return purchases.get(purchaseId, Rounding.PAISE);
  }
}
