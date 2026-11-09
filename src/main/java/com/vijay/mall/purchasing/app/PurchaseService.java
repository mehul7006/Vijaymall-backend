package com.vijay.mall.purchasing.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.purchasing.infra.PurchaseRepository.Header;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receiving stock from a supplier: one document per delivery with the supplier, their invoice number and, per product, how
 * many arrived and what each cost. Receiving adds the stock (reason PURCHASE, linked to the document) in the same
 * transaction, once per idempotency key.
 */
@Service
public class PurchaseService {
  /** One product received: quantity (1 to {@link #MAX_QTY}) and the cost of each unit in paise. */
  public record ItemInput(UUID productId, int qty, long unitCostPaise) {}

  /** A supplier delivery to record: supplier name, optional invoice number and note, and the product lines. */
  public record Input(String supplierName, String invoiceNo, String note, List<ItemInput> items) {}

  /** A received line with the product name and the line cost in paise (quantity times unit cost). */
  public record ItemView(UUID productId, String name, int qty, long unitCostPaise, long lineCostPaise) {}

  /** A recorded delivery with its lines. Money is paise. */
  public record PurchaseView(UUID id, String supplierName, String invoiceNo, String note, long totalCostPaise, UUID createdBy,
                             Instant createdAt, List<ItemView> items) {}

  /** Most product lines in one delivery. */
  public static final int MAX_LINES = 200;
  /** Largest quantity of one product in a delivery. */
  public static final int MAX_QTY = 10_000_000;
  /** Rs 10 lakh per unit: anything above is a typing mistake */
  public static final long MAX_UNIT_COST = 1_000_000_00L;

  private final PurchaseRepository repo;
  private final InventoryService inventory;
  private final CatalogService catalog;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  /**
   * Creates the service. The transaction template lets {@code receive} catch a duplicate-key failure outside the transaction.
   */
  public PurchaseService(PurchaseRepository repo, InventoryService inventory, CatalogService catalog, AuditService audit,
                         TransactionTemplate tx, Clock clock) {
    this.repo = repo;
    this.inventory = inventory;
    this.catalog = catalog;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  /** Records a delivery and adds its stock. A repeated key returns the first result without adding stock again. */
  public PurchaseView receive(UUID actor, String idemKey, Input in) {
    if (idemKey == null || idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
    var existing = repo.findByIdem(idemKey);
    if (existing.isPresent()) return replay(existing.get(), in);
    try {
      UUID id = tx.execute(s -> doReceive(actor, idemKey, in));
      return view(repo.find(id).orElseThrow());
    } catch (DataIntegrityViolationException e) {
      return replay(repo.findByIdem(idemKey).orElseThrow(() -> e), in);
    }
  }

  /**
   * The answer to a repeated idempotency key: the first delivery, but only when this request asks for the same delivery
   * (same supplier, invoice number and lines). The same key with a different delivery is refused instead of being swallowed.
   */
  private PurchaseView replay(PurchaseRepository.Header stored, Input in) {
    List<ItemInput> asked = in.items() == null ? List.of() : in.items();
    var sameLines = new HashSet<>(repo.lines(stored.id()).stream()
        .map(l -> new ItemInput(l.productId(), l.qty(), l.unitCostPaise())).toList());
    boolean same = Objects.toString(stored.supplierName(), "").equals(in.supplierName() == null ? "" : in.supplierName().strip())
        && Objects.toString(stored.invoiceNo(), "").equals(in.invoiceNo() == null ? "" : in.invoiceNo().strip())
        && sameLines.equals(new HashSet<>(asked));
    if (!same) {
      throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different delivery: use a new key", Map.of());
    }
    return view(stored);
  }

  private UUID doReceive(UUID actor, String idemKey, Input in) {
    String supplier = in.supplierName() == null ? "" : in.supplierName().strip();
    String invoice = in.invoiceNo() == null ? "" : in.invoiceNo().strip();
    String note = in.note() == null ? "" : in.note().strip();
    if (supplier.isEmpty() || supplier.length() > 120) throw ApiException.validation("supplierName", "1 to 120 characters");
    if (invoice.length() > 60) throw ApiException.validation("invoiceNo", "at most 60 characters");
    if (note.length() > 300) throw ApiException.validation("note", "at most 300 characters");
    List<ItemInput> lines = in.items() == null ? List.of() : in.items();
    if (lines.isEmpty() || lines.size() > MAX_LINES) throw ApiException.validation("items", "1 to " + MAX_LINES + " products");

    Set<UUID> seen = new HashSet<>();
    long total = 0;
    for (ItemInput l : lines) {
      if (l.productId() == null) throw ApiException.validation("productId", "is required");
      if (!seen.add(l.productId())) throw ApiException.validation("items", "a product appears twice: add its quantities together");
      if (l.qty() <= 0 || l.qty() > MAX_QTY) throw ApiException.validation("qty", "1 to " + MAX_QTY);
      if (l.unitCostPaise() < 0 || l.unitCostPaise() > MAX_UNIT_COST) throw ApiException.validation("unitCostPaise", "check the cost");
      total = Math.addExact(total, Math.multiplyExact((long) l.qty(), l.unitCostPaise()));
    }
    if (catalog.names(seen).size() != seen.size()) throw ApiException.validation("productId", "a product does not exist");

    var now = clock.instant();
    List<PurchaseRepository.Line> stored = lines.stream()
        .map(l -> new PurchaseRepository.Line(l.productId(), l.qty(), l.unitCostPaise())).toList();
    UUID id = repo.insert(supplier, invoice, note, total, idemKey, actor, stored, now);
    for (ItemInput l : lines) inventory.adjust(l.productId(), l.qty(), "PURCHASE", actor, id);
    audit.log(actor, "PURCHASE", "purchase", id, supplier + " " + invoice + " total=" + total);
    return id;
  }

  /** The delivery with this id; {@code NOT_FOUND} when unknown. */
  public PurchaseView get(UUID id) { return view(repo.find(id).orElseThrow(() -> ApiException.notFound("Purchase"))); }

  /** Deliveries, newest first. */
  public Page<PurchaseView> list(int page, int size) {
    Page<Header> p = repo.page(Math.max(0, page), Page.clampSize(size));
    return p.withItems(p.items().stream().map(this::view).toList());
  }

  private PurchaseView view(Header h) {
    List<PurchaseRepository.Line> lines = repo.lines(h.id());
    Map<UUID, String> names = catalog.names(lines.stream().map(PurchaseRepository.Line::productId).toList());
    List<ItemView> items = lines.stream().map(l -> new ItemView(l.productId(), names.getOrDefault(l.productId(), "?"), l.qty(),
        l.unitCostPaise(), l.qty() * l.unitCostPaise())).toList();
    return new PurchaseView(h.id(), h.supplierName(), h.invoiceNo(), h.note(), h.totalCostPaise(), h.createdBy(), h.createdAt(), items);
  }
}
