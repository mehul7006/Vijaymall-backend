package com.vijay.mall.flash.domain;

import java.util.Optional;

/** Sale price arithmetic. Money is paise; every result is exact. */
public final class FlashMath {
  private FlashMath() {}

  /** A sale price is never below Rs 1. */
  public static final long MIN_PRICE_PAISE = 100;
  public static final int MIN_PERCENT = 1;
  public static final int MAX_PERCENT = 90;

  /** The discount that applies to an item: its own when it names one, else the sale's default. */
  public record Rule(FlashKind kind, long value) {
    public boolean usable() { return kind != FlashKind.NONE; }
  }

  public static Rule effective(FlashKind itemKind, long itemValue, FlashKind saleKind, long saleValue) {
    return itemKind != null && itemKind != FlashKind.NONE ? new Rule(itemKind, itemValue) : new Rule(saleKind, saleValue);
  }

  /**
   * The sale price for the product's CURRENT list price: percent off rounded half-up to the paisa, or the fixed price; never
   * below Rs 1 and never above the list price. Empty when that leaves no discount (a list price of Rs 1 or less, or a fixed price
   * the list price has since fallen to).
   */
  public static Optional<Long> salePrice(Rule rule, long listPricePaise) {
    if (rule == null || !rule.usable() || listPricePaise <= MIN_PRICE_PAISE) return Optional.empty();
    long raw = rule.kind() == FlashKind.PERCENT ? Math.floorDiv(Math.multiplyExact(listPricePaise, 100 - rule.value()) + 50, 100) : rule.value();
    long price = Math.min(listPricePaise, Math.max(MIN_PRICE_PAISE, raw));
    return price < listPricePaise ? Optional.of(price) : Optional.empty();
  }

  /** Whole percent saved (rounded to the nearest). */
  public static int discountPercent(long listPricePaise, long salePricePaise) {
    if (listPricePaise <= 0) return 0;
    return (int) Math.floorDiv((listPricePaise - salePricePaise) * 100 * 2 + listPricePaise, listPricePaise * 2);
  }

  /** Checks a rule as typed by staff (before any product is looked at); returns the problem or null. */
  public static String problemWith(FlashKind kind, long value) {
    return switch (kind) {
      case PERCENT -> value < MIN_PERCENT || value > MAX_PERCENT ? "percent must be " + MIN_PERCENT + " to " + MAX_PERCENT : null;
      case FIXED_PRICE -> value < MIN_PRICE_PAISE ? "sale price must be at least Rs 1" : null;
      case NONE -> null;
    };
  }

  /** A fixed price must be below the list price when the sale is set up. */
  public static boolean fixedPriceBelow(Rule rule, long listPricePaise) {
    return rule.kind() != FlashKind.FIXED_PRICE || rule.value() < listPricePaise;
  }

  /** How many units go at the sale price: the request, cut to what is left of the cap and of the customer's allowance. */
  public static int saleQty(int requested, Integer unitsLeft, Integer customerLeft) {
    int n = requested;
    if (unitsLeft != null) n = Math.min(n, unitsLeft);
    if (customerLeft != null) n = Math.min(n, customerLeft);
    return Math.max(0, n);
  }
}
