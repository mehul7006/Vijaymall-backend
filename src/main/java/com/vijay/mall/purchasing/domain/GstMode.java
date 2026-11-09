package com.vijay.mall.purchasing.domain;

import com.vijay.mall.common.error.ApiException;
import java.util.Locale;

/** How GST paid to the supplier is treated on one purchase. */
public enum GstMode {
  /** GST is claimed back as input credit, so it is not part of the product cost. */
  REGULAR,
  /** The shop is a composition dealer: no credit, all GST is part of the cost. */
  COMPOSITION,
  /** The purchase carries no GST at all. */
  NONE;

  /** Whether the tax paid is claimed back (and so left out of the cost). */
  public boolean creditable() { return this == REGULAR; }

  /**
   * Reads the text sent by the app (any case).
   *
   * @param text the value, or null/blank for "use the default"
   * @param fallback what to answer for null/blank
   * @return the mode
   * @throws ApiException 400 on field {@code gstMode} for an unknown value
   */
  public static GstMode parse(String text, GstMode fallback) {
    if (text == null || text.isBlank()) return fallback;
    try {
      return valueOf(text.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("gstMode", "REGULAR, COMPOSITION or NONE");
    }
  }
}
