package com.vijay.mall.catalog.domain;

import java.util.UUID;

/**
 * What customers see. {@code flash} is set while a flash sale covers the product: pricePaise stays the normal price and
 * flash.salePricePaise is what the customer pays (for the units left in the sale). Packs of one product (250 g, 500 g, 1 kg) are separate rows that share productGroupId.
 * rating is the average of reviews (0 when there are none).
 */
public record Product(UUID id, UUID categoryId, String nameEn, String nameGu, String description,
                      long mrpPaise, long pricePaise, String unit, String packSize, String imageUrl,
                      boolean inStock, int qtyAvailable, boolean hotSale, boolean earnsCoins,
                      boolean coinRedeemable, UUID productGroupId, double rating, int reviewCount,
                      com.vijay.mall.common.FlashPricing.Offer flash) {

  /** A product not on a flash sale. */
  public Product(UUID id, UUID categoryId, String nameEn, String nameGu, String description,
                 long mrpPaise, long pricePaise, String unit, String packSize, String imageUrl,
                 boolean inStock, int qtyAvailable, boolean hotSale, boolean earnsCoins,
                 boolean coinRedeemable, UUID productGroupId, double rating, int reviewCount) {
    this(id, categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit, packSize, imageUrl, inStock, qtyAvailable, hotSale,
        earnsCoins, coinRedeemable, productGroupId, rating, reviewCount, null);
  }

  public Product withFlash(com.vijay.mall.common.FlashPricing.Offer offer) {
    return new Product(id, categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit, packSize, imageUrl, inStock, qtyAvailable,
        hotSale, earnsCoins, coinRedeemable, productGroupId, rating, reviewCount, offer);
  }
}
