package com.vijay.mall.flash.domain;

import java.util.Locale;

/** How a sale price is worked out: a percentage off the list price, a fixed sale price, or (item level only) "use the sale's default". */
public enum FlashKind {
  PERCENT, FIXED_PRICE, NONE;

  /**
   * Reads a discount kind typed by staff, ignoring case and surrounding spaces.
   * @param text {@code PERCENT}, {@code FIXED_PRICE} or {@code NONE}; null or blank means {@code NONE}
   * @return the matching kind
   * @throws IllegalArgumentException when the text is not one of the three kinds
   */
  public static FlashKind parse(String text) {
    if (text == null || text.isBlank()) return NONE;
    try {
      return valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("must be PERCENT, FIXED_PRICE or NONE");
    }
  }
}
