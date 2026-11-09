package com.vijay.mall.common.live;

import java.time.Instant;

/**
 * Something every screen shows changed: area is CATALOG (products, prices, categories, banners, variants), COUPONS,
 * UNITS, SHOP (name, address, phone, details) or PAYMENT_METHODS. Sent to every stream; the app refetches that area.
 */
public record CatalogChanged(String area, Instant at) {
  /** Area: products, prices, categories, banners, variants. */
  public static final String CATALOG = "CATALOG";
  /** Area: coupons offered in the app. */
  public static final String COUPONS = "COUPONS";
  /** Area: units of measure. */
  public static final String UNITS = "UNITS";
  /** Area: shop name, address, phone and details. */
  public static final String SHOP = "SHOP";
  /** Area: which payment methods are switched on. */
  public static final String PAYMENT_METHODS = "PAYMENT_METHODS";
  /** Flash sales (created, changed, started, ended): the app refetches the sale list and the product prices. */
  public static final String FLASH = "FLASH";
}
