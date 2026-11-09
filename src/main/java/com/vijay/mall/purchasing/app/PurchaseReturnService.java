package com.vijay.mall.purchasing.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import com.vijay.mall.purchasing.infra.PurchaseRepository.Line;
import com.vijay.mall.purchasing.infra.PurchaseReturnRepository;
import com.vijay.mall.purchasing.infra.PurchaseReturnRepository.Amounts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Returning goods to a supplier (a debit note). A return takes the units out of stock (reason PURCHASE_RETURN, refused when the stock is not
 * there), gets a debit note number, reduces what the shop owes (it can go negative: the supplier then owes the shop) and reverses the GST in
 * the input tax report. The value of a returned unit is its share of the line: the line's taxable value and taxes divided over paid plus free
 * units; the last unit returned takes the exact remainder, so a fully returned line gives back exactly what it cost. Freight and round-off
 * are not returned.
 */
@Service
public class PurchaseReturnService {
  /** One line to send back: the purchase line and how many units. */
  @Schema(name = "PurchaseReturnItemInput")
  public record ItemInput(@NotNull UUID purchaseItemId, @Min(1) int qty) {}

  /** A return to record: the lines and why. */
  @Schema(name = "PurchaseReturnInput")
  public record Input(@NotEmpty @Size(max = PurchaseService.MAX_LINES) List<@Valid ItemInput> items, @NotBlank @Size(max = 200) String reason) {}

  /** A returned line with the credit it gives, in paise. */
  @Schema(name = "PurchaseReturnItemView")
  public record ItemView(UUID purchaseItemId, UUID productId, String name, int qty, long taxablePaise, long cgstPaise, long sgstPaise, long igstPaise,
                         long cessPaise, long totalPaise) {}

  /** A debit note. {@code totalPaise} is the credit including GST. */
  @Schema(name = "PurchaseReturnView")
  public record ReturnView(UUID id, UUID purchaseId, UUID supplierId, String supplierName, String invoiceNo, String noteNo, String reason,
                           long taxablePaise, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise, long totalPaise, UUID createdBy,
                           Instant createdAt, List<ItemView> items) {}

  private final PurchaseRepository purchases;
  private final PurchaseReturnRepository repo;
  private final InventoryService inventory;
  private final CatalogService catalog;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final AdminOps ops;

  /** Creates the service. */
  public PurchaseReturnService(PurchaseRepository purchases, PurchaseReturnRepository repo, InventoryService inventory, CatalogService catalog,
                               AuditService audit, TransactionTemplate tx, Clock clock, AdminOps ops) {
    this.purchases = purchases;
    this.repo = repo;
    this.inventory = inventory;
    this.catalog = catalog;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
    this.ops = ops;
  }

  /**
   * Records a return against a delivery. Needs an Idempotency-Key: a repeat returns the first debit note and moves no stock.
   *
   * @throws ApiException 404 unknown delivery; 400 for a line that is not on the delivery, a repeated line or a missing reason; 422
   *     {@code RETURN_EXCEEDS_RECEIVED} for more units than were received and not yet returned; 422 {@code INSUFFICIENT_STOCK_FOR_RETURN}
   *     when the shop no longer has the units
   */
  public ReturnView create(UUID actor, String idemKey, UUID purchaseId, Input in) {
    PurchaseService.requireKey(idemKey);
    return ops.once(idemKey, "purchase-return", actor, AdminOps.fingerprint(purchaseId, in),
        () -> tx.execute(s -> doCreate(actor, idemKey, purchaseId, in)), v -> v.id().toString(), ref -> get(UUID.fromString(ref)));
  }

  private ReturnView doCreate(UUID actor, String idemKey, UUID purchaseId, Input in) {
    String reason = in.reason().strip();
    List<ItemInput> asked = in.items();
    purchases.findForUpdate(purchaseId).orElseThrow(() -> ApiException.notFound("Purchase"));
    Map<UUID, Line> lines = new HashMap<>();
    purchases.lines(purchaseId).forEach(l -> lines.put(l.id(), l));
    Set<UUID> seen = new HashSet<>();
    for (ItemInput i : asked) {
      if (!lines.containsKey(i.purchaseItemId())) throw ApiException.validation("purchaseItemId", "not a line of this purchase");
      if (!seen.add(i.purchaseItemId())) throw ApiException.validation("items", "a line appears twice: add its quantities together");
    }
    Map<UUID, Amounts> done = repo.returnedSoFar(List.copyOf(seen));
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    List<PurchaseReturnRepository.Item> items = new ArrayList<>();
    Amounts total = Amounts.ZERO;
    for (ItemInput i : asked) {
      Line l = lines.get(i.purchaseItemId());
      int units = l.qty() + l.freeQty();
      if (i.qty() > units - l.returnedQty()) {
        throw ApiException.unprocessable("RETURN_EXCEEDS_RECEIVED", "More units than were received and not yet returned",
            Map.of("fields", Map.of("qty", "at most " + (units - l.returnedQty())), "purchaseItemId", l.id()));
      }
      int onHand = inventory.quantityForUpdate(l.productId());
      if (onHand < i.qty()) {
        throw ApiException.unprocessable("INSUFFICIENT_STOCK_FOR_RETURN", "The shop no longer has these units in stock",
            Map.of("fields", Map.of("qty", "only " + onHand + " in stock"), "productId", l.productId(), "onHand", onHand));
      }
      Amounts credit = credit(l, i.qty(), units, done.getOrDefault(l.id(), Amounts.ZERO));
      items.add(new PurchaseReturnRepository.Item(UUID.randomUUID(), id, l.id(), l.productId(), i.qty(), credit));
      total = total.plus(credit);
    }
    String noteNo = repo.nextNoteNo();
    repo.insert(new PurchaseReturnRepository.Return(id, purchaseId, null, null, null, noteNo, reason, total, actor, now), idemKey, items);
    for (var item : items) {
      purchases.addReturnedQty(item.purchaseItemId(), item.qty());
      inventory.returnToSupplier(item.productId(), item.qty(), purchaseId, id, actor);
    }
    purchases.addReturned(purchaseId, total.totalPaise());
    audit.log(actor, "PURCHASE_RETURN", "purchase", purchaseId, noteNo + " credit=" + total.totalPaise() + " " + reason);
    return get(id);
  }

  /** The credit for {@code qty} units of a line: a proportional share, or the exact remainder when it returns the last units. */
  static Amounts credit(Line l, int qty, int units, Amounts done) {
    if (qty == units - l.returnedQty()) {
      long taxable = l.taxablePaise() - done.taxablePaise();
      long cgst = l.cgstPaise() - done.cgstPaise();
      long sgst = l.sgstPaise() - done.sgstPaise();
      long igst = l.igstPaise() - done.igstPaise();
      long cess = l.cessPaise() - done.cessPaise();
      return new Amounts(taxable, cgst, sgst, igst, cess, taxable + cgst + sgst + igst + cess);
    }
    long taxable = share(l.taxablePaise(), qty, units);
    long cgst = share(l.cgstPaise(), qty, units);
    long sgst = share(l.sgstPaise(), qty, units);
    long igst = share(l.igstPaise(), qty, units);
    long cess = share(l.cessPaise(), qty, units);
    return new Amounts(taxable, cgst, sgst, igst, cess, taxable + cgst + sgst + igst + cess);
  }

  private static long share(long value, int qty, int units) { return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(qty)).divide(BigDecimal.valueOf(units), 0, RoundingMode.HALF_UP).longValueExact(); }

  /** One debit note; 404 when unknown. */
  public ReturnView get(UUID id) {
    return views(List.of(repo.find(id).orElseThrow(() -> ApiException.notFound("Purchase return")))).getFirst();
  }

  /** Debit notes, newest first; {@code from} and {@code to} are India dates (inclusive). */
  public Page<ReturnView> list(UUID purchaseId, UUID supplierId, LocalDate from, LocalDate to, int page, int size) {
    if (from != null && to != null && from.isAfter(to)) throw ApiException.validation("from", "must not be after to");
    var filter = new PurchaseReturnRepository.Filter(purchaseId, supplierId,
        from == null ? null : from.atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant(),
        to == null ? null : to.plusDays(1).atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant());
    Page<PurchaseReturnRepository.Return> p = repo.page(filter, Math.max(0, page), Page.clampSize(size));
    return p.withItems(views(p.items()));
  }

  private List<ReturnView> views(List<PurchaseReturnRepository.Return> returns) {
    Map<UUID, List<PurchaseReturnRepository.Item>> items = repo.itemsOf(returns.stream().map(PurchaseReturnRepository.Return::id).toList());
    Map<UUID, String> names = catalog.names(items.values().stream().flatMap(List::stream).map(PurchaseReturnRepository.Item::productId).distinct().toList());
    return returns.stream().map(r -> {
      Amounts a = r.amounts();
      List<ItemView> lines = items.getOrDefault(r.id(), List.of()).stream().map(i -> new ItemView(i.purchaseItemId(), i.productId(),
          names.getOrDefault(i.productId(), "?"), i.qty(), i.amounts().taxablePaise(), i.amounts().cgstPaise(), i.amounts().sgstPaise(),
          i.amounts().igstPaise(), i.amounts().cessPaise(), i.amounts().totalPaise())).toList();
      return new ReturnView(r.id(), r.purchaseId(), r.supplierId(), r.supplierName(), r.invoiceNo(), r.noteNo(), r.reason(), a.taxablePaise(),
          a.cgstPaise(), a.sgstPaise(), a.igstPaise(), a.cessPaise(), a.totalPaise(), r.createdBy(), r.createdAt(), lines);
    }).toList();
  }
}
