package com.vijay.mall.purchasing.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.app.ProductPatch;
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.purchasing.domain.GstMode;
import com.vijay.mall.purchasing.domain.PurchaseMath;
import com.vijay.mall.purchasing.domain.Rounding;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import com.vijay.mall.purchasing.infra.PurchaseRepository.Header;
import com.vijay.mall.purchasing.infra.PurchaseRepository.Line;
import com.vijay.mall.purchasing.infra.PurchaseRepository.PriceRow;
import com.vijay.mall.purchasing.infra.SupplierRepository.Supplier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receiving stock from a supplier: one document per delivery with the supplier, their invoice, the GST maths, per-product lines and the
 * payments made against it. Receiving adds the stock (reason PURCHASE, linked to the document) in the same transaction, once per
 * idempotency key. All money is exact paise; per-unit landed costs keep 4 decimals of a paisa (see {@link PurchaseMath}).
 *
 * <p>The payment status and the amount due are derived from the stored totals and payments, never taken from the app (except that the
 * paid amount and mode given when the delivery is recorded become its first payment).
 */
@Service
public class PurchaseService {
  /**
   * One product received. Only the product, the quantity and the unit cost (before tax, in paise) are required; everything else is optional:
   * free units, a line discount (percent wins when above zero), GST and cess rates (GST defaults to the product's), HSN (defaults to the
   * product's), the MRP and selling price printed on the pack, batch and expiry, and whether to apply the MRP and price to the product now.
   */
  @Schema(name = "PurchaseItemInput")
  public record ItemInput(@NotNull UUID productId,
                          @Min(value = 1, message = "must be 1 to 1000000") @Max(value = 1_000_000, message = "must be 1 to 1000000") int qty,
                          @Min(value = 0, message = "must be at least 0") @Max(value = 100_000_000, message = "must be at most 100000000") long unitCostPaise,
                          Integer freeQty, BigDecimal discountPercent, Long discountPaise, Integer gstPercent, BigDecimal cessPercent,
                          @Size(max = 20) String hsn, Long mrpPaise, Long sellingPricePaise, @Size(max = 40) String batchNo,
                          LocalDate expiryDate, Boolean applyToVariant) {
  }

  /**
   * A supplier delivery to record. The supplier is {@code supplierId} or, for older apps, {@code supplierName} (a supplier is found by name or
   * created). Optional: invoice number and date, received date, note, attachment note (free text or link), supplier phone, GSTIN and state, the
   * GST mode (default from the shop setting {@code purchase.gstMode}), a document discount (percent wins when above zero), freight with its
   * GST percent, {@code roundToRupee}, the total the app expects ({@code expectedTotalPaise}, refused when different from the server's),
   * and the payment made now (status PAID, PARTIAL or PENDING, paid amount, mode, reference, money account) and the due date.
   */
  @Schema(name = "PurchaseInput")
  public record Input(UUID supplierId, @Size(max = 120) String supplierName, @Size(max = 60) String invoiceNo, @Size(max = 200) String note,
                      @NotNull @Size(min = 1, max = MAX_LINES) List<@Valid ItemInput> items, LocalDate invoiceDate, LocalDate receivedDate,
                      @Size(max = 500) String attachmentNote, @Size(max = 20) String supplierPhone, @Size(max = 15) String supplierGstin,
                      @Size(max = 2) String supplierStateCode, @Size(max = 12) String gstMode, BigDecimal docDiscountPercent, Long docDiscountPaise,
                      Long freightPaise, Integer freightGstPercent, Boolean roundToRupee, Long expectedTotalPaise,
                      @Size(max = 10) String paymentStatus, Long paidPaise, @Size(max = 10) String paymentMode, LocalDate dueDate,
                      @Size(max = 80) String paymentRef, UUID moneyAccountId) {
  }

  /** A payment to record against a delivery. {@code paidAt} defaults to now; {@code moneyAccountId} is stored but not validated yet. */
  @Schema(name = "PurchasePaymentInput")
  public record PaymentInput(@Min(1) long amountPaise, @NotNull @Size(max = 10) String mode, @Size(max = 80) String reference,
                             @Size(max = 200) String note, Instant paidAt, UUID moneyAccountId) {}

  /** A received line with its computed figures. Unit costs are paise; the landed cost has 4 decimals and a rounded twin. */
  @Schema(name = "PurchaseItemView")
  public record ItemView(UUID id, UUID productId, String name, int qty, int freeQty, long unitCostPaise, long lineCostPaise,
                         BigDecimal discountPercent, long lineDiscountPaise, long docDiscountSharePaise, long taxablePaise, int gstPercent,
                         long cgstPaise, long sgstPaise, long igstPaise, BigDecimal cessPercent, long cessPaise, long lineTotalPaise, String hsn,
                         Long mrpPaise, Long sellingPricePaise, String batchNo, LocalDate expiryDate, boolean applyToVariant, int returnedQty,
                         long landedTotalPaise, BigDecimal landedUnitCostPaise, BigDecimal landedUnitCostRoundedPaise) {}

  /** A payment made to the supplier. */
  @Schema(name = "PurchasePaymentView")
  public record PaymentView(UUID id, long amountPaise, String mode, String reference, String note, UUID moneyAccountId, Instant paidAt,
                            UUID createdBy) {}

  /**
   * A recorded delivery. Money is paise; {@code totalCostPaise} is what the supplier is owed for the document and {@code duePaise} what is
   * still owed after debit notes and payments (negative: the supplier owes the shop). {@code rounding} says how
   * {@code landedUnitCostRoundedPaise} of each line was rounded. {@code warnings} is filled only in the answer to recording a delivery.
   */
  @Schema(name = "PurchaseView")
  public record PurchaseView(UUID id, UUID supplierId, String supplierName, String supplierPhone, String supplierGstin, String supplierStateCode,
                             boolean interState, String gstMode, String invoiceNo, LocalDate invoiceDate, LocalDate receivedDate, String note,
                             String attachmentNote, long lineDiscountPaise, BigDecimal docDiscountPercent, long docDiscountPaise, long taxablePaise,
                             long cgstPaise, long sgstPaise, long igstPaise, long cessPaise, long freightPaise, int freightGstPercent,
                             long freightGstPaise, long roundOffPaise, long totalCostPaise, String paymentStatus, long paidPaise,
                             long returnedPaise, long duePaise, LocalDate dueDate, boolean overdue, List<PaymentView> payments, String rounding,
                             UUID createdBy, Instant createdAt, List<ItemView> items, List<String> warnings) {
    PurchaseView withWarnings(List<String> found) {
      return new PurchaseView(id, supplierId, supplierName, supplierPhone, supplierGstin, supplierStateCode, interState, gstMode, invoiceNo,
          invoiceDate, receivedDate, note, attachmentNote, lineDiscountPaise, docDiscountPercent, docDiscountPaise, taxablePaise, cgstPaise,
          sgstPaise, igstPaise, cessPaise, freightPaise, freightGstPercent, freightGstPaise, roundOffPaise, totalCostPaise, paymentStatus,
          paidPaise, returnedPaise, duePaise, dueDate, overdue, payments, rounding, createdBy, createdAt, items, found);
    }
  }

  /** What the form needs when a product is picked: its tax defaults and prices, and what the last purchase cost. */
  @Schema(name = "PurchaseLookup")
  public record Lookup(UUID productId, String name, int gstPercent, String hsn, long mrpPaise, long pricePaise, int qtyOnHand,
                       Long lastPurchaseCostPaise, BigDecimal lastLandedUnitCostPaise, String lastSupplierName, LocalDate lastInvoiceDate,
                       UUID lastPurchaseId) {}

  /** One earlier purchase of a product. */
  @Schema(name = "PurchasePriceView")
  public record PriceView(UUID purchaseId, UUID supplierId, String supplierName, String invoiceNo, LocalDate invoiceDate, int qty, int freeQty,
                          long unitCostPaise, BigDecimal landedUnitCostPaise, BigDecimal landedUnitCostRoundedPaise, Long mrpPaise,
                          Long sellingPricePaise, Instant createdAt) {}

  /** One supplier in the payables summary. */
  @Schema(name = "PurchaseSupplierDueView")
  public record SupplierDueView(UUID supplierId, String name, long totalPurchasedPaise, long totalPaidPaise, long totalDuePaise, long overduePaise,
                                long overdueCount) {}

  /**
   * What the shop owes suppliers. Purchased is net of debit notes, so purchased minus paid is the due amount. {@code bySupplier} lists the
   * suppliers with a balance (up to 200, most owed first; a negative balance means the supplier owes the shop).
   */
  @Schema(name = "PurchasePayables")
  public record Payables(long totalPurchasedPaise, long totalReturnedPaise, long totalPaidPaise, long totalDuePaise, long overduePaise,
                         long overdueCount, List<SupplierDueView> bySupplier) {}

  /** Most product lines in one delivery. */
  public static final int MAX_LINES = 200;
  /** Largest quantity of one product in a delivery. */
  public static final int MAX_QTY = 1_000_000;
  /** Rs 10 lakh per unit: anything above is a typing mistake */
  public static final long MAX_UNIT_COST = 1_000_000_00L;
  /** The payment modes. */
  public static final Set<String> MODES = Set.of("CASH", "UPI", "CARD", "BANK", "CHEQUE");
  private static final Pattern HSN = Pattern.compile("^[0-9]{4,8}$");
  private static final int PAYABLE_SUPPLIERS = 200;

  private final PurchaseRepository repo;
  private final InventoryService inventory;
  private final CatalogService catalog;
  private final AuditService audit;
  private final SupplierService suppliers;
  private final PosCatalogService pos;
  private final ShopService shop;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final AdminOps ops;
  private final RuntimeSettings settings;

  /**
   * Creates the service. The transaction template lets {@code receive} catch a duplicate-key failure outside the transaction; {@code ops}
   * makes a repeated payment harmless and {@code settings} gives the default GST mode.
   */
  public PurchaseService(PurchaseRepository repo, InventoryService inventory, CatalogService catalog, AuditService audit, SupplierService suppliers,
                         PosCatalogService pos, ShopService shop, TransactionTemplate tx, Clock clock, AdminOps ops, RuntimeSettings settings) {
    this.repo = repo;
    this.inventory = inventory;
    this.catalog = catalog;
    this.audit = audit;
    this.suppliers = suppliers;
    this.pos = pos;
    this.shop = shop;
    this.tx = tx;
    this.clock = clock;
    this.ops = ops;
    this.settings = settings;
  }

  private LocalDate today() { return LocalDate.now(clock.withZone(InvoiceNumbers.SHOP_ZONE)); }

  private static String text(String v) { return v == null ? "" : v.strip(); }

  /** Records a delivery and adds its stock. A repeated key returns the first result without adding stock again. */
  public PurchaseView receive(UUID actor, String idemKey, Input in) {
    requireKey(idemKey);
    var existing = repo.findByIdem(idemKey);
    if (existing.isPresent()) return replay(existing.get(), in);
    try {
      UUID id = tx.execute(s -> doReceive(actor, idemKey, in));
      return recorded(repo.find(id).orElseThrow());
    } catch (DataIntegrityViolationException e) {
      return replay(repo.findByIdem(idemKey).orElseThrow(() -> e), in);
    }
  }

  /**
   * The answer to a repeated idempotency key: the first delivery, but only when this request asks for the same delivery. The same key with a
   * different delivery is refused instead of being swallowed. Deliveries recorded before the request fingerprint existed are compared by
   * supplier and invoice number.
   */
  private PurchaseView replay(Header stored, Input in) {
    boolean same = stored.requestHash() != null ? stored.requestHash().equals(AdminOps.fingerprint(in))
        : Objects.toString(stored.supplierName(), "").equalsIgnoreCase(text(in.supplierName())) && Objects.toString(stored.invoiceNo(), "").equals(text(in.invoiceNo()));
    if (!same) {
      throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different delivery: use a new key", Map.of());
    }
    return recorded(stored);
  }

  /** A blank key would switch the repeat protection off, so it is refused (the header being absent is refused by the web layer). */
  static void requireKey(String idemKey) {
    if (idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
  }

  private PurchaseView recorded(Header h) { return views(List.of(h), Rounding.PAISE).getFirst().withWarnings(warnings(h)); }

  /**
   * Warns (never blocks) when an earlier delivery has the same supplier and invoice number, compared trimmed and ignoring case. Worked
   * out from the stored delivery, so a repeated request answers with the same warning.
   */
  private List<String> warnings(Header h) {
    return repo.earlierWithInvoice(h.supplierName(), h.invoiceNo(), h.id(), h.createdAt())
        .map(e -> List.of("invoice number " + h.invoiceNo().strip() + " was already recorded for this supplier on "
            + e.createdAt().atZone(InvoiceNumbers.SHOP_ZONE).toLocalDate() + " (purchase " + e.id() + ")"))
        .orElse(List.of());
  }

  private GstMode defaultMode() {
    return GstMode.parse(settings.get(Settings.PURCHASE_GST_MODE), GstMode.REGULAR);
  }

  private static int rate(Integer given, int fallback, String field) {
    int r = given == null ? fallback : given;
    if (!PosCatalogService.GST_RATES.contains(r)) throw ApiException.validation(field, "one of " + PosCatalogService.GST_RATES);
    return r;
  }

  private static BigDecimal percent(BigDecimal p, String field) {
    if (p == null) return BigDecimal.ZERO;
    if (p.signum() < 0 || p.compareTo(BigDecimal.valueOf(100)) > 0 || p.stripTrailingZeros().scale() > 2) {
      throw ApiException.validation(field, "0 to 100 with at most 2 decimals");
    }
    return p;
  }

  private static long money(Long v, String field) {
    if (v == null) return 0;
    if (v < 0 || v > MAX_UNIT_COST * MAX_QTY) throw ApiException.validation(field, "check the amount");
    return v;
  }

  private UUID doReceive(UUID actor, String idemKey, Input in) {
    String invoice = text(in.invoiceNo());
    String note = text(in.note());
    String attachment = text(in.attachmentNote());
    List<ItemInput> items = in.items();
    LocalDate today = today();
    LocalDate invoiceDate = in.invoiceDate() == null ? today : in.invoiceDate();
    LocalDate receivedDate = in.receivedDate() == null ? today : in.receivedDate();
    if (invoiceDate.isAfter(today)) throw ApiException.validation("invoiceDate", "cannot be in the future");
    if (receivedDate.isAfter(today)) throw ApiException.validation("receivedDate", "cannot be in the future");
    GstMode mode = GstMode.parse(in.gstMode(), defaultMode());

    Set<UUID> seen = new HashSet<>();
    for (ItemInput l : items) {
      if (!seen.add(l.productId())) throw ApiException.validation("items", "a product appears twice: add its quantities together");
    }
    if (catalog.names(seen).size() != seen.size()) throw ApiException.validation("productId", "a product does not exist");
    Map<UUID, ProductPos> defaults = pos.attributesFor(seen);

    List<PurchaseMath.LineIn> mathLines = new ArrayList<>();
    for (ItemInput l : items) mathLines.add(lineIn(l, defaults.get(l.productId())));
    BigDecimal docPercent = percent(in.docDiscountPercent(), "docDiscountPercent");
    long docAmount = money(in.docDiscountPaise(), "docDiscountPaise");
    if (docPercent.signum() > 0 && docAmount > 0) throw ApiException.validation("docDiscountPaise", "give either a percent or an amount");
    long freight = money(in.freightPaise(), "freightPaise");
    int freightRate = freight == 0 ? 0 : rate(in.freightGstPercent(), 0, "freightGstPercent");

    Supplier supplier = suppliers.resolve(actor, in.supplierId(), in.supplierName(), in.supplierPhone(), in.supplierGstin(), in.supplierStateCode());
    String supplierState = supplierState(in.supplierStateCode(), supplier);
    boolean interState = !supplierState.isEmpty() && !supplierState.equals(shop.get().stateCode());

    var math = PurchaseMath.compute(mathLines, new PurchaseMath.DocIn(mode, interState, docPercent, docAmount, freight, freightRate,
        Boolean.TRUE.equals(in.roundToRupee())));
    if (in.expectedTotalPaise() != null && in.expectedTotalPaise() != math.totalPaise()) {
      throw ApiException.unprocessable("TOTAL_MISMATCH", "The total sent does not match the total worked out from the lines",
          Map.of("fields", Map.of("expectedTotalPaise", "server total is " + math.totalPaise()), "expectedTotalPaise", in.expectedTotalPaise(),
              "totalPaise", math.totalPaise()));
    }

    UUID id = UUID.randomUUID();
    var now = clock.instant();
    PaymentPlan pay = paymentPlan(in, math.totalPaise());
    LocalDate dueDate = dueDate(in.dueDate(), invoiceDate, supplier);
    List<Line> lines = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) lines.add(line(items.get(i), mathLines.get(i), math.lines().get(i), defaults.get(items.get(i).productId())));
    Header header = new Header(id, supplier.id(), supplier.name(), supplier.phone(), supplier.gstin(), supplierState, interState, mode.name(), invoice,
        invoiceDate, receivedDate, note, attachment, math.lineDiscountPaise(), docPercent.signum() > 0 ? docPercent : null, math.docDiscountPaise(),
        math.taxablePaise(), math.cgstPaise(), math.sgstPaise(), math.igstPaise(), math.cessPaise(), freight, freightRate, math.freightGstPaise(),
        math.roundOffPaise(), math.totalPaise(), dueDate, pay.paid(), 0, 0, null, AdminOps.fingerprint(in), actor, now);
    PurchaseRepository.Payment first = pay.paid() == 0 ? null
        : new PurchaseRepository.Payment(UUID.randomUUID(), id, pay.paid(), pay.mode(), pay.reference(), "", in.moneyAccountId(), now, actor, now);
    repo.insert(header, idemKey, lines, first);

    applyToVariants(actor, id, items);
    receiveStock(actor, id, items);
    audit.log(actor, "PURCHASE", "purchase", id, supplier.name() + " " + invoice + " total=" + math.totalPaise());
    return id;
  }

  private static String supplierState(String given, Supplier supplier) {
    String s = text(given);
    return s.isEmpty() ? supplier.stateCode() : s;
  }

  private PurchaseMath.LineIn lineIn(ItemInput l, ProductPos product) {
    int free = l.freeQty() == null ? 0 : l.freeQty();
    if (free < 0 || (long) free + l.qty() > MAX_QTY) throw ApiException.validation("freeQty", "0 to " + (MAX_QTY - l.qty()));
    BigDecimal discountPercent = percent(l.discountPercent(), "discountPercent");
    long discountPaise = money(l.discountPaise(), "discountPaise");
    if (discountPercent.signum() > 0 && discountPaise > 0) throw ApiException.validation("discountPaise", "give either a percent or an amount");
    int gst = rate(l.gstPercent(), product.gstPercent(), "gstPercent");
    if (!text(l.hsn()).isEmpty() && !HSN.matcher(text(l.hsn())).matches()) throw ApiException.validation("hsn", "4 to 8 digits");
    long mrp = l.mrpPaise() == null ? 0 : money(l.mrpPaise(), "mrpPaise");
    long price = l.sellingPricePaise() == null ? 0 : money(l.sellingPricePaise(), "sellingPricePaise");
    if (l.mrpPaise() != null && l.sellingPricePaise() != null && price > mrp) throw ApiException.validation("sellingPricePaise", "cannot be above the MRP");
    if (Boolean.TRUE.equals(l.applyToVariant()) && l.mrpPaise() == null && l.sellingPricePaise() == null) {
      throw ApiException.validation("applyToVariant", "give the MRP or the selling price to apply");
    }
    return new PurchaseMath.LineIn(l.qty(), free, l.unitCostPaise(), discountPercent, discountPaise, gst, percent(l.cessPercent(), "cessPercent"));
  }

  private static Line line(ItemInput l, PurchaseMath.LineIn in, PurchaseMath.LineOut out, ProductPos product) {
    String hsn = text(l.hsn()).isEmpty() ? product.hsn() : text(l.hsn());
    return new Line(UUID.randomUUID(), l.productId(), l.qty(), in.freeQty(), l.unitCostPaise(), in.discountPercent().signum() > 0 ? in.discountPercent() : null,
        out.lineDiscountPaise(), out.docDiscountSharePaise(), out.taxablePaise(), in.gstPercent(), out.cgstPaise(), out.sgstPaise(), out.igstPaise(),
        in.cessPercent(), out.cessPaise(), out.lineTotalPaise(), out.landedTotalPaise(), out.landedUnitCost(), hsn, l.mrpPaise(), l.sellingPricePaise(),
        text(l.batchNo()), l.expiryDate(), Boolean.TRUE.equals(l.applyToVariant()), 0);
  }

  /** What is paid when the delivery is recorded. */
  private record PaymentPlan(long paid, String mode, String reference) {}

  private PaymentPlan paymentPlan(Input in, long total) {
    String status = text(in.paymentStatus()).toUpperCase(Locale.ROOT);
    if (!status.isEmpty() && !Set.of("PAID", "PARTIAL", "PENDING").contains(status)) {
      throw ApiException.validation("paymentStatus", "PAID, PARTIAL or PENDING");
    }
    long given = in.paidPaise() == null ? -1 : in.paidPaise();
    if (in.paidPaise() != null && (given < 0 || given > total)) throw ApiException.validation("paidPaise", "0 to the total (" + total + ")");
    if (status.isEmpty()) status = given <= 0 ? "PENDING" : given == total ? "PAID" : "PARTIAL";
    long paid = switch (status) {
      case "PENDING" -> {
        if (given > 0) throw ApiException.validation("paidPaise", "must be 0 or left out when the payment is PENDING");
        yield 0;
      }
      case "PAID" -> {
        if (given >= 0 && given != total) throw ApiException.validation("paidPaise", "must equal the total (" + total + ") when PAID");
        yield total;
      }
      default -> {
        if (given <= 0 || given >= total) throw ApiException.validation("paidPaise", "must be more than 0 and less than the total (" + total + ") when PARTIAL");
        yield given;
      }
    };
    String ref = text(in.paymentRef());
    String mode = text(in.paymentMode()).toUpperCase(Locale.ROOT);
    if (paid > 0 && !MODES.contains(mode)) throw ApiException.validation("paymentMode", "CASH, UPI, CARD, BANK or CHEQUE");
    return new PaymentPlan(paid, mode, ref);
  }

  private static LocalDate dueDate(LocalDate given, LocalDate invoiceDate, Supplier supplier) {
    if (given != null) {
      if (given.isBefore(invoiceDate)) throw ApiException.validation("dueDate", "cannot be before the invoice date");
      return given;
    }
    return supplier.paymentTermsDays() > 0 ? invoiceDate.plusDays(supplier.paymentTermsDays()) : null;
  }

  /** Updates the product's MRP and selling price for the lines that ask for it (the admin's choice); each change is audited. */
  private void applyToVariants(UUID actor, UUID purchaseId, List<ItemInput> items) {
    for (ItemInput l : items) {
      if (!Boolean.TRUE.equals(l.applyToVariant())) continue;
      AdminProduct before = catalog.adminGet(l.productId());
      Map<String, Object> patch = new LinkedHashMap<>();
      if (l.mrpPaise() != null) patch.put("mrpPaise", l.mrpPaise());
      if (l.sellingPricePaise() != null) patch.put("pricePaise", l.sellingPricePaise());
      AdminProduct after = catalog.patch(l.productId(), new ProductPatch(patch), actor);
      audit.log(actor, "PURCHASE_PRICE_APPLY", "product", l.productId(), "mrp " + before.mrpPaise() + " -> " + after.mrpPaise() + ", price "
          + before.pricePaise() + " -> " + after.pricePaise() + " (purchase " + purchaseId + ")");
    }
  }

  /**
   * The one place where a received delivery becomes stock: each line adds its paid and free units (reason PURCHASE, linked to the
   * delivery). The stock-lot work replaces this method's {@code inventory.adjust(...)} call to open a lot with the line's landed cost instead.
   */
  private void receiveStock(UUID actor, UUID purchaseId, List<ItemInput> items) {
    for (ItemInput l : items) inventory.adjust(l.productId(), l.qty() + (l.freeQty() == null ? 0 : l.freeQty()), "PURCHASE", actor, purchaseId);
  }

  /** Records a payment against a delivery. Needs an Idempotency-Key. 409 {@code ALREADY_PAID} when nothing is due; 422 {@code PAYMENT_EXCEEDS_DUE} above the due amount. */
  public PurchaseView addPayment(UUID actor, String idemKey, UUID purchaseId, PaymentInput in) {
    requireKey(idemKey);
    return ops.once(idemKey, "purchase-payment", actor, AdminOps.fingerprint(purchaseId, in),
        () -> tx.execute(s -> doPay(actor, purchaseId, in)), v -> v.id().toString(), ref -> get(UUID.fromString(ref), Rounding.PAISE));
  }

  private PurchaseView doPay(UUID actor, UUID purchaseId, PaymentInput in) {
    String mode = text(in.mode()).toUpperCase(Locale.ROOT);
    if (!MODES.contains(mode)) throw ApiException.validation("mode", "CASH, UPI, CARD, BANK or CHEQUE");
    String reference = text(in.reference());
    String note = text(in.note());
    var now = clock.instant();
    if (in.paidAt() != null && in.paidAt().isAfter(now.plus(10, ChronoUnit.MINUTES))) throw ApiException.validation("paidAt", "cannot be in the future");
    Header h = repo.findForUpdate(purchaseId).orElseThrow(() -> ApiException.notFound("Purchase"));
    if (h.duePaise() <= 0) {
      throw ApiException.conflict("ALREADY_PAID", "Nothing is due on this purchase", Map.of("duePaise", h.duePaise()));
    }
    if (in.amountPaise() > h.duePaise()) {
      throw ApiException.unprocessable("PAYMENT_EXCEEDS_DUE", "The payment is more than the amount due",
          Map.of("fields", Map.of("amountPaise", "at most " + h.duePaise()), "duePaise", h.duePaise()));
    }
    repo.insertPayment(new PurchaseRepository.Payment(UUID.randomUUID(), purchaseId, in.amountPaise(), mode, reference, note, in.moneyAccountId(),
        in.paidAt() == null ? now : in.paidAt(), actor, now));
    repo.addPaid(purchaseId, in.amountPaise());
    audit.log(actor, "PURCHASE_PAYMENT", "purchase", purchaseId, mode + " " + in.amountPaise() + " " + reference);
    return get(purchaseId, Rounding.PAISE);
  }

  /** The delivery with this id; {@code NOT_FOUND} when unknown. */
  public PurchaseView get(UUID id, Rounding rounding) {
    return views(List.of(repo.find(id).orElseThrow(() -> ApiException.notFound("Purchase"))), rounding).getFirst();
  }

  /** Deliveries, newest first. {@code paymentStatus} is PENDING, PARTIAL or PAID; {@code supplier} matches the name by contains. */
  public Page<PurchaseView> list(PurchaseRepository.Filter filter, int page, int size, Rounding rounding) {
    Page<Header> p = repo.page(filter, Math.max(0, page), Page.clampSize(size));
    return p.withItems(views(p.items(), rounding));
  }

  /** Builds the list filter from the request parameters. */
  public PurchaseRepository.Filter filter(String paymentStatus, UUID supplierId, String supplier, boolean overdue, LocalDate from, LocalDate to) {
    String status = text(paymentStatus).toUpperCase(Locale.ROOT);
    if (!status.isEmpty() && !Set.of("PAID", "PARTIAL", "PENDING").contains(status)) throw ApiException.validation("paymentStatus", "PAID, PARTIAL or PENDING");
    if (from != null && to != null && from.isAfter(to)) throw ApiException.validation("from", "must not be after to");
    return new PurchaseRepository.Filter(status.isEmpty() ? null : status, supplierId, text(supplier).isEmpty() ? null : text(supplier), overdue, from, to,
        today());
  }

  /** What the shop owes its suppliers. */
  public Payables payables() {
    LocalDate today = today();
    var t = repo.payablesTotals(today);
    List<SupplierDueView> by = repo.payablesBySupplier(today, PAYABLE_SUPPLIERS).stream()
        .map(s -> new SupplierDueView(s.supplierId(), s.name(), s.purchasedPaise(), s.paidPaise(), s.duePaise(), s.overduePaise(), s.overdueCount())).toList();
    return new Payables(t.purchasedPaise() - t.returnedPaise(), t.returnedPaise(), t.paidPaise(), t.duePaise(), t.overduePaise(), t.overdueCount(), by);
  }

  /** The product's tax defaults, prices and what it cost last time, for the purchase form. */
  public Lookup lookup(UUID productId) {
    AdminProduct p = catalog.adminGet(productId);
    ProductPos attrs = pos.attributesFor(List.of(productId)).get(productId);
    PriceRow last = repo.priceHistory(productId, 1).stream().findFirst().orElse(null);
    return new Lookup(productId, p.nameEn(), attrs.gstPercent(), attrs.hsn(), p.mrpPaise(), p.pricePaise(),
        p.qtyOnHand(), last == null ? null : last.unitCostPaise(), last == null ? null : last.landedUnitCost(), last == null ? null : last.supplierName(),
        last == null ? null : last.invoiceDate(), last == null ? null : last.purchaseId());
  }

  /** The last {@code limit} (1 to 100) purchases of a product, newest first, with each supplier's cost. */
  public List<PriceView> priceHistory(UUID productId, int limit, Rounding rounding) {
    catalog.adminGet(productId);
    return repo.priceHistory(productId, Math.max(1, Math.min(limit, 100))).stream()
        .map(r -> new PriceView(r.purchaseId(), r.supplierId(), r.supplierName(), r.invoiceNo(), r.invoiceDate(), r.qty(), r.freeQty(), r.unitCostPaise(),
            r.landedUnitCost().setScale(4), rounding.apply(r.landedUnitCost()), r.mrpPaise(), r.sellingPricePaise(), r.createdAt())).toList();
  }

  /** Builds views for many deliveries with one query each for lines, payments and product names. */
  private List<PurchaseView> views(List<Header> headers, Rounding rounding) {
    List<UUID> ids = headers.stream().map(Header::id).toList();
    Map<UUID, List<Line>> lines = repo.linesOf(ids);
    Map<UUID, List<PurchaseRepository.Payment>> payments = repo.paymentsOf(ids);
    Map<UUID, String> names = catalog.names(lines.values().stream().flatMap(List::stream).map(Line::productId).distinct().toList());
    LocalDate today = today();
    return headers.stream().map(h -> {
      List<ItemView> items = lines.getOrDefault(h.id(), List.of()).stream().map(l -> item(l, names.getOrDefault(l.productId(), "?"), rounding)).toList();
      List<PaymentView> paid = payments.getOrDefault(h.id(), List.of()).stream()
          .map(p -> new PaymentView(p.id(), p.amountPaise(), p.mode(), p.reference(), p.note(), p.moneyAccountId(), p.paidAt(), p.createdBy())).toList();
      boolean overdue = h.dueDate() != null && h.dueDate().isBefore(today) && h.duePaise() > 0;
      return new PurchaseView(h.id(), h.supplierId(), h.supplierName(), h.supplierPhone(), h.supplierGstin(), h.supplierStateCode(), h.interState(),
          h.gstMode(), h.invoiceNo(), h.invoiceDate(), h.receivedDate(), h.note(), h.attachmentNote(), h.lineDiscountPaise(), h.docDiscountPercent(),
          h.docDiscountPaise(), h.taxablePaise(), h.cgstPaise(), h.sgstPaise(), h.igstPaise(), h.cessPaise(), h.freightPaise(), h.freightGstPercent(),
          h.freightGstPaise(), h.roundOffPaise(), h.totalCostPaise(), h.paymentStatus(), h.paidPaise(), h.returnedPaise(), h.duePaise(), h.dueDate(),
          overdue, paid, rounding.name(), h.createdBy(), h.createdAt(), items, List.of());
    }).toList();
  }

  private static ItemView item(Line l, String name, Rounding rounding) {
    return new ItemView(l.id(), l.productId(), name, l.qty(), l.freeQty(), l.unitCostPaise(), l.qty() * l.unitCostPaise(), l.discountPercent(),
        l.lineDiscountPaise(), l.docDiscountSharePaise(), l.taxablePaise(), l.gstPercent(), l.cgstPaise(), l.sgstPaise(), l.igstPaise(), l.cessPercent(),
        l.cessPaise(), l.lineTotalPaise(), l.hsn(), l.mrpPaise(), l.sellingPricePaise(), l.batchNo(), l.expiryDate(), l.applyToVariant(), l.returnedQty(),
        l.landedTotalPaise(), l.landedUnitCost().setScale(4), rounding.apply(l.landedUnitCost()));
  }
}
