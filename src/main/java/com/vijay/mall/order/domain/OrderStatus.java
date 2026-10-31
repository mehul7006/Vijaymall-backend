package com.vijay.mall.order.domain;

import java.util.Set;

/** Order life cycle. Moves not listed here are rejected. */
public enum OrderStatus {
  PAYMENT_PENDING, PLACED, CONFIRMED, PACKED, OUT_FOR_DELIVERY, READY_FOR_PICKUP, DELIVERED, PICKED_UP, CANCELLED;

  public Set<OrderStatus> next(Fulfilment f) {
    return switch (this) {
      case PAYMENT_PENDING -> Set.of(PLACED, CANCELLED);
      case PLACED -> Set.of(CONFIRMED, CANCELLED);
      case CONFIRMED -> Set.of(PACKED, CANCELLED);
      case PACKED -> f == Fulfilment.DELIVERY
          ? Set.of(OUT_FOR_DELIVERY, CANCELLED)
          : Set.of(READY_FOR_PICKUP, CANCELLED);
      case OUT_FOR_DELIVERY -> Set.of(DELIVERED);
      case READY_FOR_PICKUP -> Set.of(PICKED_UP);
      case DELIVERED, PICKED_UP, CANCELLED -> Set.of();
    };
  }

  public boolean canMoveTo(OrderStatus target, Fulfilment f) { return next(f).contains(target); }

  public boolean isFinal() { return next(Fulfilment.DELIVERY).isEmpty(); }

  /** Customers may cancel only before the shop starts packing. */
  public boolean customerCanCancel() { return this == PAYMENT_PENDING || this == PLACED || this == CONFIRMED; }

  /** Reaching this status credits earned coins. */
  public boolean completesOrder() { return this == DELIVERED || this == PICKED_UP; }
}
