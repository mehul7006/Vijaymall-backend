package com.vijay.mall.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * One batch of a product's stock with what is left of it. {@code unitCost} has four decimals of a paisa (landed cost);
 * {@code costKnown} is false for lots made from stock that had no purchase behind it. {@code pricePaise} and {@code mrpPaise} are
 * null when the lot has none of its own (the product's price then applies).
 */
public record Lot(UUID id, UUID productId, Instant receivedAt, int qtyReceived, int qtyRemaining, BigDecimal unitCost, boolean costKnown,
                  Long mrpPaise, Long pricePaise, String batchNo, LocalDate expiryDate, String source) {
  /** The order stock is taken in: oldest received first. */
  public static final Comparator<Lot> FIFO = Comparator.comparing(Lot::receivedAt).thenComparing(Lot::id);

  /** Earliest expiry first (lots without a date last), then oldest received first. */
  public static final Comparator<Lot> FEFO = Comparator
      .comparing((Lot l) -> l.expiryDate() == null ? LocalDate.MAX : l.expiryDate()).thenComparing(FIFO);

  /** The same lot with a different quantity left. */
  public Lot withRemaining(int remaining) {
    return new Lot(id, productId, receivedAt, qtyReceived, remaining, unitCost, costKnown, mrpPaise, pricePaise, batchNo, expiryDate, source);
  }

  /** Whether selling one unit from this lot or from {@code other} would be at a different price or MRP (a missing price means "the product's"). */
  public boolean priceDiffersFrom(Lot other) {
    return !Objects.equals(pricePaise, other.pricePaise) || !Objects.equals(mrpPaise, other.mrpPaise);
  }
}
