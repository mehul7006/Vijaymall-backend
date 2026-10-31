package com.vijay.mall.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** A product as stored by the catalog. Stock lives in the inventory module and is added by the service. */
public record ProductData(UUID id, UUID categoryId, String nameEn, String nameGu, String description, long mrpPaise,
                          long pricePaise, String unit, String packSize, String imageUrl, boolean active,
                          boolean hotSale, Instant hotStart, Instant hotEnd, boolean earnsCoins,
                          boolean coinRedeemable) {

  public AdminProduct withStock(int qtyOnHand, int lowThreshold) {
    return new AdminProduct(id, categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit, packSize, imageUrl,
        qtyOnHand > 0, Math.min(qtyOnHand, 99), hotSale, earnsCoins, coinRedeemable, qtyOnHand, lowThreshold, active,
        hotStart, hotEnd);
  }
}
