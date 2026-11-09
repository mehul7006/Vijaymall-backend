package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.Page;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores supplier deliveries: the header in {@code purchase} and the lines in {@code purchase_item}. */
@Repository
public class PurchaseRepository {
  /** A delivery's header; {@code totalCostPaise} is the sum of quantity times unit cost over the lines. */
  public record Header(UUID id, String supplierName, String invoiceNo, String note, long totalCostPaise, UUID createdBy,
                       Instant createdAt) {}

  /** One received product line: quantity and the cost of each unit in paise. */
  public record Line(UUID productId, int qty, long unitCostPaise) {}

  private final PurchaseJpa purchases;
  private final PurchaseItemJpa items;

  PurchaseRepository(PurchaseJpa purchases, PurchaseItemJpa items) {
    this.purchases = purchases;
    this.items = items;
  }

  private static Header toHeader(PurchaseEntity e) {
    return new Header(e.id, e.supplierName, e.invoiceNo, e.note, e.totalCostPaise, e.createdBy, e.createdAt);
  }

  /** Finds a delivery header by id. */
  public Optional<Header> find(UUID id) { return purchases.findById(id).map(PurchaseRepository::toHeader); }

  /** Finds the delivery already recorded under this idempotency key. */
  public Optional<Header> findByIdem(String key) { return purchases.findByIdemKey(key).map(PurchaseRepository::toHeader); }

  /**
   * The oldest other delivery recorded no later than {@code upTo} with the same supplier and invoice number, compared trimmed and
   * ignoring case; empty when the invoice number is blank or nothing matches.
   */
  public Optional<Header> earlierWithInvoice(String supplier, String invoiceNo, UUID self, Instant upTo) {
    String invoice = invoiceNo.strip().toLowerCase(Locale.ROOT);
    if (invoice.isEmpty()) return Optional.empty();
    return purchases.earlierWithInvoice(supplier.strip().toLowerCase(Locale.ROOT), invoice, self, upTo,
        PageRequest.of(0, 1)).stream().findFirst().map(PurchaseRepository::toHeader);
  }

  /** The lines of one delivery. */
  public List<Line> lines(UUID purchaseId) {
    return items.findByPurchaseId(purchaseId).stream().map(i -> new Line(i.productId, i.qty, i.unitCostPaise)).toList();
  }

  /** Flushes at once so a repeated idempotency key fails here, inside the caller's transaction. */
  @Transactional
  public UUID insert(String supplier, String invoiceNo, String note, long total, String idemKey, UUID actor, List<Line> lines,
                     Instant now) {
    PurchaseEntity e = new PurchaseEntity();
    e.id = UUID.randomUUID();
    e.supplierName = supplier;
    e.invoiceNo = invoiceNo;
    e.note = note;
    e.totalCostPaise = total;
    e.idemKey = idemKey;
    e.createdBy = actor;
    e.createdAt = now;
    purchases.saveAndFlush(e);
    for (Line l : lines) {
      PurchaseItemEntity i = new PurchaseItemEntity();
      i.id = UUID.randomUUID();
      i.purchaseId = e.id;
      i.productId = l.productId();
      i.qty = l.qty();
      i.unitCostPaise = l.unitCostPaise();
      items.save(i);
    }
    return e.id;
  }

  /** Newest first. */
  public Page<Header> page(int page, int size) {
    var r = purchases.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    return new Page<>(r.getContent().stream().map(PurchaseRepository::toHeader).toList(), page, size, r.getTotalElements());
  }
}
