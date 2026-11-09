package com.vijay.mall.notification.domain;

import java.util.List;

/**
 * One parameter of a segment condition, as the validator checks it and as the segment builder screen renders it.
 *
 * @param name the JSON field name inside the condition
 * @param kind how the value is entered and checked
 * @param label short English caption for the form field
 * @param required true when the condition cannot be saved without it
 * @param min smallest allowed number (or smallest list length); null when there is no lower bound
 * @param max largest allowed number (or longest list); null when there is no upper bound
 * @param options the allowed values of an {@code ENUM}; null for other kinds
 * @param pattern regular expression every text value (or list item) must match; null for none
 * @param pickFrom the admin endpoint the app uses to let the admin pick the id, for the id kinds; null otherwise
 * @param inputUnit for {@code AMOUNT_PAISE}: {@code RUPEES}, the unit the admin types in; null for other kinds
 * @param scale for {@code AMOUNT_PAISE}: what to multiply the typed number by before sending (100, because the wire value is paise); null otherwise
 */
public record Param(String name, Kind kind, String label, boolean required, Long min, Long max, List<String> options,
                    String pattern, String pickFrom, String inputUnit, Integer scale) {

  /** A parameter that is not an amount (no input unit, no scale). */
  public Param(String name, Kind kind, String label, boolean required, Long min, Long max, List<String> options, String pattern,
               String pickFrom) {
    this(name, kind, label, required, min, max, options, pattern, pickFrom, null, null);
  }

  /** How a parameter value is entered and checked. */
  public enum Kind { INT, AMOUNT_PAISE, ENUM, PRODUCT_ID, CATEGORY_ID, STRING, STRING_LIST, BOOLEAN }

  static final String PRODUCT_PICKER = "GET /api/v1/admin/products?q={text}&page=0&size=20 (use items[].id)";
  static final String CATEGORY_PICKER = "GET /api/v1/categories (use each item id)";

  /** A whole number between {@code min} and {@code max}. */
  static Param integer(String name, String label, boolean required, long min, long max) {
    return new Param(name, Kind.INT, label, required, min, max, null, null, null);
  }

  /** A day count, 1 to 3650. */
  static Param days(String name, String label, boolean required) { return integer(name, label, required, 1, 3650); }

  /**
   * Money, 0 or more. The wire value is paise (min and max are in paise); the admin types rupees, so the form multiplies by {@code scale}
   * 100 before sending.
   */
  static Param amount(String name, String label, boolean required) {
    return new Param(name, Kind.AMOUNT_PAISE, label, required, 0L, 10_000_000_000L, null, null, null, "RUPEES", 100);
  }

  /** One of a fixed set of words. */
  static Param choice(String name, String label, boolean required, String... options) {
    return new Param(name, Kind.ENUM, label, required, null, null, List.of(options), null, null);
  }

  /** A product id. */
  static Param product(String label) { return new Param("productId", Kind.PRODUCT_ID, label, true, null, null, null, null, PRODUCT_PICKER); }

  /** A category id. */
  static Param category(String label) { return new Param("categoryId", Kind.CATEGORY_ID, label, true, null, null, null, null, CATEGORY_PICKER); }

  /** Short text that must match {@code pattern}. */
  static Param text(String name, String label, String pattern) { return new Param(name, Kind.STRING, label, false, null, null, null, pattern, null); }

  /** A list of 1 to {@code max} short texts that each match {@code pattern}. */
  static Param list(String name, String label, long max, String pattern) {
    return new Param(name, Kind.STRING_LIST, label, true, 1L, max, null, pattern, null);
  }

  /** A yes/no value. */
  static Param flag(String name, String label) { return new Param(name, Kind.BOOLEAN, label, true, null, null, null, null, null); }
}
