package com.vijay.mall.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** A product as stored by the catalog. Stock lives in the inventory module and is added by the service. */
public record ProductData(UUID id, UUID categoryId, String nameEn, String nameGu, String description, long mrpPaise,
                          long pricePaise, String unit, String packSize, String imageUrl, boolean active,
                          boolean hotSale, Instant hotStart, Instant hotEnd, boolean earnsCoins,
                          boolean coinRedeemable, UUID productGroupId, double rating, int reviewCount) {

  /** A product that is its own group and has no reviews. */
  public ProductData(UUID id, UUID categoryId, String nameEn, String nameGu, String description, long mrpPaise,
                     long pricePaise, String unit, String packSize, String imageUrl, boolean active,
                     boolean hotSale, Instant hotStart, Instant hotEnd, boolean earnsCoins,
                     boolean coinRedeemable) {
    this(id, categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit, packSize, imageUrl, active,
        hotSale, hotStart, hotEnd, earnsCoins, coinRedeemable, id, 0, 0);
  }

  /** Text shown next to the price: the pack size, or the unit when the product has no pack size. */
  public String unitLabel() { return packSize == null || packSize.isBlank() ? unit : packSize; }

  /**
   * Combines this stored product with its stock numbers from the inventory module.
   * <p>{@code inStock} is true when {@code qtyOnHand} is above zero, and the customer-facing {@code qtyAvailable} is capped at 99
   * so the exact stock level is not revealed.
   * @param qtyOnHand units physically on the shelf
   * @param lowThreshold quantity at or below which the product shows up in low-stock alerts
   * @return the admin view of the product
   */
  public AdminProduct withStock(int qtyOnHand, int lowThreshold) {
    return new AdminProduct(id, categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit, packSize, imageUrl,
        qtyOnHand > 0, Math.min(qtyOnHand, 99), hotSale, earnsCoins, coinRedeemable, qtyOnHand, lowThreshold, active,
        hotStart, hotEnd, productGroupId, rating, reviewCount);
  }
}
