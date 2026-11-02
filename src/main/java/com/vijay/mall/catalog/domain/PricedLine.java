package com.vijay.mall.catalog.domain;

import java.util.UUID;

/** One cart line priced by the server from the current catalog. */
public record PricedLine(UUID productId, UUID categoryId, String name, int qty, long unitPricePaise,
                         long lineTotalPaise, boolean coinRedeemable, boolean earnsCoins, String nameGu,
                         String unitLabel) {

  /** A line with no Gujarati name or pack label of its own (counter sales); the English name stands in. */
  public PricedLine(UUID productId, UUID categoryId, String name, int qty, long unitPricePaise,
                    long lineTotalPaise, boolean coinRedeemable, boolean earnsCoins) {
    this(productId, categoryId, name, qty, unitPricePaise, lineTotalPaise, coinRedeemable, earnsCoins, name, "");
  }
}
