package com.vijay.mall.inventory.infra;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InventoryRepository {
  public record Stock(UUID productId, int qty, int low) {}

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

  @Transactional
  public void setThreshold(UUID productId, int low) { jpa.setThreshold(productId, low); }

  public int quantity(UUID productId) { return jpa.findById(productId).map(e -> e.qtyOnHand).orElse(0); }

  public List<Stock> stocks(Collection<UUID> productIds) {
    if (productIds.isEmpty()) return List.of();
    return jpa.byIds(productIds).stream().map(e -> new Stock(e.productId, e.qtyOnHand, e.lowThreshold)).toList();
  }

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

  /** Products at or below their own threshold, emptiest first. The caller filters inactive products. */
  public List<Stock> lowStock(int limit) {
    return jpa.lowStock(PageRequest.of(0, limit)).stream().map(e -> new Stock(e.productId, e.qtyOnHand, e.lowThreshold)).toList();
  }
}
