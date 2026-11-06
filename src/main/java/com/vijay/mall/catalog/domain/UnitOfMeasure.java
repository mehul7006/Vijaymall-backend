package com.vijay.mall.catalog.domain;

/**
 * A unit products are sold in (piece, dozen, gram, kilogram, litre...). The admin manages the list; code is what
 * product.unit stores. soldBy is UNIT (counted) or WEIGHT (priced per 1000 base units, weighed at the counter).
 */
public record UnitOfMeasure(String code, String nameEn, String nameGu, String soldBy, int sortOrder, boolean active) {
  public static final String UNIT = "UNIT";
  public static final String WEIGHT = "WEIGHT";
}
