package com.vijay.mall.order.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order life cycle. Staff may move an open order to any other status, in any order. Three statuses are closed:
 * a delivered or picked-up order has already paid out its coins, and a cancelled one has already given back its
 * stock, coupon use, coins and money, so none of them can be changed again. PAYMENT_PENDING is set by the system
 * when an online payment starts and cannot be chosen by hand.
 */
public enum OrderStatus {
  PAYMENT_PENDING, PLACED, CONFIRMED, PACKED, OUT_FOR_DELIVERY, READY_FOR_PICKUP, DELIVERED, PICKED_UP, CANCELLED;

  /** Statuses this one may move to. {@code f} is kept for callers; the rules no longer depend on it. */
  public Set<OrderStatus> next(Fulfilment f) {
    if (isFinal()) return Set.of();
    // Waiting for money: only the payment result (PLACED) or a cancel can move it. Staff cannot push it along unpaid.
    if (this == PAYMENT_PENDING) return EnumSet.of(PLACED, CANCELLED);
    Set<OrderStatus> all = EnumSet.allOf(OrderStatus.class);
    all.remove(this);
    all.remove(PAYMENT_PENDING);
    return all;
  }

  public boolean canMoveTo(OrderStatus target, Fulfilment f) { return next(f).contains(target); }

  /** Delivered, picked up or cancelled: nothing can change any more. */
  public boolean isFinal() { return this == DELIVERED || this == PICKED_UP || this == CANCELLED; }

  /** Customers may cancel only before the shop starts packing. */
  public boolean customerCanCancel() { return this == PAYMENT_PENDING || this == PLACED || this == CONFIRMED; }

  /**
   * Online orders take their stock when staff confirm them, so from CONFIRMED onwards the order holds stock.
   * PLACED (and PAYMENT_PENDING) have not committed any shelf stock yet.
   */
  public boolean holdsStock() {
    return this != PAYMENT_PENDING && this != PLACED && this != CANCELLED;
  }

  /** Reaching this status credits earned coins. */
  public boolean completesOrder() { return this == DELIVERED || this == PICKED_UP; }
}
