package com.vijay.mall.catalog.domain;

import java.util.UUID;

/**
 * One cart line priced by the server from the current catalog. A flash sale splits a product into up to two lines: the units at
 * the sale price (flashSaleId set, listUnitPricePaise the normal price) and the rest at the normal price (flashSaleId null).
 * productGroupId is the product the variant belongs to (item coupons can name a whole product).
 */
public record PricedLine(UUID productId, UUID categoryId, String name, int qty, long unitPricePaise,
                         long lineTotalPaise, boolean coinRedeemable, boolean earnsCoins, String nameGu,
                         String unitLabel, UUID flashSaleId, long listUnitPricePaise, UUID productGroupId) {

  /** A line whose product group is not known (it then matches no product-group coupon). */
  public PricedLine(UUID productId, UUID categoryId, String name, int qty, long unitPricePaise,
                    long lineTotalPaise, boolean coinRedeemable, boolean earnsCoins, String nameGu,
                    String unitLabel, UUID flashSaleId, long listUnitPricePaise) {
    this(productId, categoryId, name, qty, unitPricePaise, lineTotalPaise, coinRedeemable, earnsCoins, nameGu, unitLabel,
        flashSaleId, listUnitPricePaise, null);
  }

  /** A line that is not on a flash sale. */
  public PricedLine(UUID productId, UUID categoryId, String name, int qty, long unitPricePaise,
                    long lineTotalPaise, boolean coinRedeemable, boolean earnsCoins, String nameGu,
                    String unitLabel) {
    this(productId, categoryId, name, qty, unitPricePaise, lineTotalPaise, coinRedeemable, earnsCoins, nameGu, unitLabel, null, 0);
  }

  /** The same product and settings as this line, for a different quantity and unit price (a flash sale split). */
  public PricedLine split(int newQty, long newUnitPricePaise, UUID saleId, long listUnitPrice) {
    return new PricedLine(productId, categoryId, name, newQty, newUnitPricePaise, newUnitPricePaise * newQty, coinRedeemable, earnsCoins,
        nameGu, unitLabel, saleId, listUnitPrice, productGroupId);
  }

  /** A line with no Gujarati name or pack label of its own (counter sales); the English name stands in. */
  public PricedLine(UUID productId, UUID categoryId, String name, int qty, long unitPricePaise,
                    long lineTotalPaise, boolean coinRedeemable, boolean earnsCoins) {
    this(productId, categoryId, name, qty, unitPricePaise, lineTotalPaise, coinRedeemable, earnsCoins, name, "");
  }
}
