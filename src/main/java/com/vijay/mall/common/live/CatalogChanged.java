package com.vijay.mall.common.live;

import java.time.Instant;

/**
 * Something every screen shows changed: area is CATALOG (products, prices, categories, banners, variants), COUPONS,
 * UNITS, SHOP (name, address, phone, details) or PAYMENT_METHODS. Sent to every stream; the app refetches that area.
 */
public record CatalogChanged(String area, Instant at) {
  public static final String CATALOG = "CATALOG";
  public static final String COUPONS = "COUPONS";
  public static final String UNITS = "UNITS";
  public static final String SHOP = "SHOP";
  public static final String PAYMENT_METHODS = "PAYMENT_METHODS";
}
