package com.vijay.mall.common;

/** Limits shared by the basket, the quote and the order calls, so they can never disagree. */
public final class OrderLimits {
  /** The most units of one product a customer can have in a basket or an order (all lines of the product added together). */
  public static final int MAX_LINE_QTY = 99;

  private OrderLimits() { }
}
