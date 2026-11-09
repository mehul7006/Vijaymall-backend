package com.vijay.mall.flash.domain;

import java.util.Locale;

/** How a sale price is worked out: a percentage off the list price, a fixed sale price, or (item level only) "use the sale's default". */
public enum FlashKind {
  PERCENT, FIXED_PRICE, NONE;

  public static FlashKind parse(String text) {
    if (text == null || text.isBlank()) return NONE;
    try {
      return valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("must be PERCENT, FIXED_PRICE or NONE");
    }
  }
}
