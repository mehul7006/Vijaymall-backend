package com.vijay.mall.common;

/** Whether the shop is taking online orders right now (the admin can pause them). Implemented by the shop profile; used when an order is placed. */
public interface OnlineOrdering {
  /** Throws a 422 ORDERS_PAUSED with the shop's own message when ordering is paused. */
  void requireOpen();
}
