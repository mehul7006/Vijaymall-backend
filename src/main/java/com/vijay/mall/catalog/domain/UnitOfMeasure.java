package com.vijay.mall.catalog.domain;

/**
 * A unit products are sold in (piece, dozen, gram, kilogram, litre...). The admin manages the list; code is what
 * product.unit stores. soldBy is UNIT (counted) or WEIGHT (priced per 1000 base units, weighed at the counter).
 */
public record UnitOfMeasure(String code, String nameEn, String nameGu, String soldBy, int sortOrder, boolean active) {
  /** {@code soldBy} value for items that are counted (pieces, packs, dozens). */
  public static final String UNIT = "UNIT";
  /** {@code soldBy} value for loose goods that are weighed at the counter and priced per 1000 base units. */
  public static final String WEIGHT = "WEIGHT";
}
