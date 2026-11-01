package com.vijay.mall.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** Product plus the fields only the shop owner sees. hotSale here is the raw flag. */
public record AdminProduct(UUID id, UUID categoryId, String nameEn, String nameGu, String description,
                           long mrpPaise, long pricePaise, String unit, String packSize, String imageUrl,
                           boolean inStock, int qtyAvailable, boolean hotSale, boolean earnsCoins,
                           boolean coinRedeemable, int qtyOnHand, int lowThreshold, boolean active,
                           Instant hotStart, Instant hotEnd, UUID productGroupId, double rating, int reviewCount) {

  public boolean effectiveHot(Instant now) {
    return hotSale && (hotStart == null || !now.isBefore(hotStart)) && (hotEnd == null || !now.isAfter(hotEnd));
  }

  public Product toCustomer(Instant now) {
    return new Product(id, categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit, packSize,
        imageUrl, inStock, qtyAvailable, effectiveHot(now), earnsCoins, coinRedeemable, productGroupId, rating, reviewCount);
  }
}
