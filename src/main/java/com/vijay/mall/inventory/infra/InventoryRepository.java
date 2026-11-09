package com.vijay.mall.inventory.infra;

import com.vijay.mall.common.Page;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock levels and the stock-movement history. Quantity changes use single conditional UPDATEs so stock can never go below zero, even with concurrent buyers.
 */
@Repository
public class InventoryRepository {
  /** A product's units on hand and its low-stock alert level ({@code low}). */
  public record Stock(UUID productId, int qty, int low) {}

  /** One line of the stock history. refId is the order or sale behind it; actorId the person who adjusted it. */
  public record Movement(UUID id, UUID productId, int delta, String reason, UUID refId, UUID actorId, Instant at) {}

  private final InventoryJpa jpa;
  private final StockMovementJpa movements;

  InventoryRepository(InventoryJpa jpa, StockMovementJpa movements) {
    this.jpa = jpa;
    this.movements = movements;
  }

  /** Creates or resets the stock row of a product. */
  @Transactional
  public void create(UUID productId, int qty, int low, Instant now) {
    InventoryEntity e = jpa.findById(productId).orElseGet(() -> {
      InventoryEntity n = new InventoryEntity();
      n.productId = productId;
      return n;
    });
    e.qtyOnHand = qty;
    e.lowThreshold = low;
    e.updatedAt = now;
    jpa.save(e);
  }

  /** Atomic: takes qty only if enough is on hand. Returns rows changed (0 = not enough). */
  @Transactional
  public int reserve(UUID productId, int qty, Instant now) { return jpa.reserve(productId, qty, now); }

  /** Adds delta (may be negative) only if the result stays at zero or above. */
  @Transactional
  public int add(UUID productId, int delta, Instant now) { return jpa.add(productId, delta, now); }

  /** Sets the low-stock alert level of a product. */
  @Transactional
  public void setThreshold(UUID productId, int low) { jpa.setThreshold(productId, low); }

  /** Units on hand; 0 when the product has no stock row. */
  public int quantity(UUID productId) { return jpa.findById(productId).map(e -> e.qtyOnHand).orElse(0); }

  /** Units on hand, read with a lock held until the transaction ends (used by stock takes); 0 when the product has no stock row. */
  @Transactional
  public int quantityForUpdate(UUID productId) { return jpa.lockById(productId).map(e -> e.qtyOnHand).orElse(0); }

  /** Stock rows of these products; a product without a row is simply absent. */
  public List<Stock> stocks(Collection<UUID> productIds) {
    if (productIds.isEmpty()) return List.of();
    return jpa.byIds(productIds).stream().map(e -> new Stock(e.productId, e.qtyOnHand, e.lowThreshold)).toList();
  }

  /**
   * Writes one line to the stock history. {@code delta} is signed (negative removes stock); {@code refId} and {@code actorId} may be null.
   */
  @Transactional
  public void movement(UUID productId, int delta, String reason, UUID refId, UUID actorId, Instant now) {
    StockMovementEntity m = new StockMovementEntity();
    m.id = UUID.randomUUID();
    m.productId = productId;
    m.delta = delta;
    m.reason = reason;
    m.refId = refId;
    m.actorId = actorId;
    m.createdAt = now;
    movements.save(m);
  }

  /** Stock history, newest first; one product, or every product when productId is null. */
  public Page<Movement> movementPage(UUID productId, int page, int size) {
    PageRequest req = PageRequest.of(page, size, Sort.by(
        Sort.Direction.DESC, "createdAt"));
    var result = productId == null ? movements.findAll(req) : movements.findByProductId(productId, req);
    List<Movement> rows = result.getContent().stream()
        .map(m -> new Movement(m.id, m.productId, m.delta, m.reason, m.refId, m.actorId, m.createdAt)).toList();
    return new Page<>(rows, page, size, result.getTotalElements());
  }

  /** How many products are at or below their own alert level. */
  public long countLow() { return jpa.countLow(); }

  /** Products at or below their own threshold, emptiest first. The caller filters inactive products. */
  public List<Stock> lowStock(int limit) {
    return jpa.lowStock(PageRequest.of(0, limit)).stream().map(e -> new Stock(e.productId, e.qtyOnHand, e.lowThreshold)).toList();
  }
}
