package com.vijay.mall.order.domain;

/** One stage of pricing. Steps run in order(); add a new discount by adding a new step. */
public interface QuoteStep {
  /**
   * Position of the step: steps run in ascending order (subtotal 100, coupon 200, coins 300, delivery 400, total 500, GST 550, coin earning 600).
   */
  int order();

  /** Reads and updates the shared context; must not touch the database for writing. */
  void apply(QuoteContext ctx);
}
