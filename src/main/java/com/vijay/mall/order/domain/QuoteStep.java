package com.vijay.mall.order.domain;

/** One stage of pricing. Steps run in order(); add a new discount by adding a new step. */
public interface QuoteStep {
  int order();

  void apply(QuoteContext ctx);
}
