package com.vijay.mall.purchasing.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.purchasing.app.PurchaseAttachmentService;
import com.vijay.mall.purchasing.app.PurchaseEditService;
import com.vijay.mall.purchasing.app.PurchaseReturnService;
import com.vijay.mall.purchasing.app.SupplierCreditService;
import com.vijay.mall.purchasing.app.PurchaseService.PurchaseView;
import com.vijay.mall.purchasing.app.PurchaseService;
import com.vijay.mall.purchasing.app.StockTakeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import com.vijay.mall.purchasing.domain.Rounding;
import java.time.LocalDate;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Stock coming in, payments to suppliers, purchase returns and stock counts. Everything under /admin is for the admin only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin")
public class PurchasingController {
  private AdminOps ops;

  /** Makes repeats of an admin action with the same Idempotency-Key harmless (plain unit tests build the controller without it). */
  @Autowired
  void setOps(AdminOps ops) { this.ops = ops; }

  /** Request body for a stock take: an optional note and at least one counted product. */
  public record StockTakeBody(@Size(max = 200) String note, @NotEmpty List<StockTakeService.Count> items) {}

  private final PurchaseService purchases;
  private final PurchaseReturnService returns;
  private final StockTakeService stockTakes;
  private final SupplierCreditService credit;
  private final PurchaseEditService edits;
  private final PurchaseAttachmentService attachments;

  /** Creates the controller. */
  public PurchasingController(PurchaseService purchases, PurchaseReturnService returns, StockTakeService stockTakes, SupplierCreditService credit,
                              PurchaseEditService edits, PurchaseAttachmentService attachments) {
    this.purchases = purchases;
    this.returns = returns;
    this.stockTakes = stockTakes;
    this.credit = credit;
    this.edits = edits;
    this.attachments = attachments;
  }

  /** Receive a supplier delivery. Needs an Idempotency-Key so a retried request cannot add the stock twice. */
  @PostMapping("/purchases")
  @ResponseStatus(HttpStatus.CREATED)
  public PurchaseView receive(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PurchaseService.Input b) {
    return purchases.receive(CurrentUser.id(), key, b);
  }

  /**
   * Lists supplier deliveries, newest first. Filters: paymentStatus (PENDING, PARTIAL, PAID), supplierId, supplier (name contains), overdue,
   * from and to (invoice dates). rounding (EXACT, PAISE, RUPEE) is how the rounded landed unit cost of each line is rounded. Admin only.
   */
  @GetMapping("/purchases")
  public Page<PurchaseView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                 @RequestParam(required = false) String paymentStatus, @RequestParam(required = false) UUID supplierId,
                                 @RequestParam(required = false) String supplier, @RequestParam(defaultValue = "false") boolean overdue,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String rounding) {
    return purchases.list(purchases.filter(paymentStatus, supplierId, supplier, overdue, from, to), page, size, Rounding.parse(rounding));
  }

  /** What the shop owes its suppliers: totals, overdue and the suppliers with a balance. Admin only. */
  @GetMapping("/purchases/payables")
  public PurchaseService.Payables payables() { return purchases.payables(); }

  /** The tax defaults, prices and last purchase cost of a product, for the purchase form. Admin only. */
  @GetMapping("/purchases/lookup")
  public PurchaseService.Lookup lookup(@RequestParam UUID productId) { return purchases.lookup(productId); }

  /** Returns one delivery with its lines and payments; 404 when unknown. Admin only. */
  @GetMapping("/purchases/{id}")
  public PurchaseView get(@PathVariable UUID id, @RequestParam(required = false) String rounding) { return purchases.get(id, Rounding.parse(rounding)); }

  /**
   * Records a payment to the supplier. Needs an Idempotency-Key; answers with the delivery as it is now. Mode CREDIT_NOTE with a
   * {@code debitNoteId} pays with the supplier's credit instead of money.
   */
  @PostMapping("/purchases/{id}/payments")
  @ResponseStatus(HttpStatus.CREATED)
  public PurchaseView pay(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PurchaseService.PaymentInput b) {
    if (SupplierCreditService.CREDIT_NOTE.equals(b.mode().strip().toUpperCase(Locale.ROOT))) return credit.payWithCredit(CurrentUser.id(), key, id, b);
    return purchases.addPayment(CurrentUser.id(), key, id, b);
  }

  /**
   * Corrects the non-stock fields of a recorded purchase: invoiceNo, invoiceDate, dueDate (null clears it), note, attachmentNote, supplierGstin
   * and supplierStateCode. Fields left out stay; any other field is refused (400) because quantities, costs and tax change stock and money.
   */
  @PatchMapping("/purchases/{id}")
  public PurchaseView edit(@PathVariable UUID id, @RequestBody Map<String, Object> body) { return edits.edit(CurrentUser.id(), id, body); }

  /** Attaches or replaces the invoice file (multipart field {@code file}: PDF, JPEG, PNG or WebP, at most 5 MB); answers with the purchase. */
  @PostMapping("/purchases/{id}/attachment")
  public PurchaseView attach(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
    return attachments.attach(CurrentUser.id(), id, file.getBytes());
  }

  /** Removes the invoice file (nothing happens when there is none); answers with the purchase. */
  @DeleteMapping("/purchases/{id}/attachment")
  public PurchaseView detach(@PathVariable UUID id) { return attachments.remove(CurrentUser.id(), id); }

  /** The invoice file, shown inline with its own content type; 404 when there is none. Never cached or sniffed. */
  @GetMapping("/purchases/{id}/attachment")
  public ResponseEntity<byte[]> attachment(@PathVariable UUID id) {
    var a = attachments.read(id);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.contentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(a.fileName()).build().toString())
        .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore().cachePrivate()).body(a.content());
  }

  /** Returns goods to the supplier (a debit note). Needs an Idempotency-Key. */
  @PostMapping("/purchases/{id}/returns")
  @ResponseStatus(HttpStatus.CREATED)
  public PurchaseReturnService.ReturnView giveBack(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody PurchaseReturnService.Input b) {
    return returns.create(CurrentUser.id(), key, id, b);
  }

  /** The debit notes of one delivery, newest first. */
  @GetMapping("/purchases/{id}/returns")
  public Page<PurchaseReturnService.ReturnView> returnsOf(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
    purchases.get(id, Rounding.PAISE);
    return returns.list(id, null, null, null, page, size);
  }

  /** Debit notes, newest first; filters: supplierId, from and to (India dates). */
  @GetMapping("/purchase-returns")
  public Page<PurchaseReturnService.ReturnView> allReturns(@RequestParam(required = false) UUID supplierId,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return returns.list(null, supplierId, from, to, page, size);
  }

  /** One debit note; 404 when unknown. */
  @GetMapping("/purchase-returns/{id}")
  public PurchaseReturnService.ReturnView oneReturn(@PathVariable UUID id) { return returns.get(id); }

  /** The last purchases of a product, newest first, with each supplier's cost (limit 1 to 100, default 20). */
  @GetMapping("/products/{id}/purchase-prices")
  public List<PurchaseService.PriceView> priceHistory(@PathVariable UUID id, @RequestParam(defaultValue = "20") int limit,
                                                      @RequestParam(required = false) String rounding) {
    return purchases.priceHistory(id, limit, Rounding.parse(rounding));
  }

  /** Sets stock to the counted figures and returns what changed. */
  @PostMapping("/stock-takes")
  public StockTakeService.Result stockTake(@Valid @RequestBody StockTakeBody b, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    // a repeat with the same key returns the first call's result and applies nothing more
    return AdminOps.runStored(ops, key, "stock-take", CurrentUser.id(), AdminOps.fingerprint(b),
        () -> stockTakes.apply(CurrentUser.id(), b.note(), b.items()), StockTakeService.Result.class);
  }
}
