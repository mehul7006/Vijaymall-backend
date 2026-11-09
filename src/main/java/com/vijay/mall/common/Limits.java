package com.vijay.mall.common;

/**
 * The size limits of the free-text and money fields the admin can type, kept in one place so every endpoint, the Swagger schemas and the
 * guides agree. Money is in paise: {@link #MAX_PRICE_PAISE} is Rs 10 lakh.
 */
public final class Limits {
  /** Product, category, unit and banner names. */
  public static final int NAME = 120;
  /** Product and coupon descriptions. */
  public static final int DESCRIPTION = 2000;
  /** Short banner or coupon texts such as a title or a subtitle. */
  public static final int TEXT = 200;
  /** Largest selling price or MRP, in paise (Rs 10 lakh). */
  public static final long MAX_PRICE_PAISE = 100_000_000L;
  /** Longest coupon code. */
  public static final int COUPON_CODE = 30;
  /** Uppercase letters, digits, dash and underscore. */
  public static final String COUPON_CODE_REGEX = "^[A-Z0-9_-]+$";
  /** Largest stock adjustment either way, in pieces. */
  public static final int MAX_STOCK_DELTA = 1_000_000;
  /** Largest quantity on one purchase line. */
  public static final int MAX_QTY = 1_000_000;
  /** Largest number of months before earned coins expire. */
  public static final int MAX_EXPIRY_MONTHS = 120;
  /** Shop name. */
  public static final int SHOP_NAME = 80;
  /** Shop address. */
  public static final int SHOP_ADDRESS = 300;
  /** Receipt footer. */
  public static final int RECEIPT_FOOTER = 200;
  /** Shop short name. */
  public static final int SHORT_NAME = 20;
  /** Blank, or only digits, spaces, + and - with 7 to 15 digits in all. */
  public static final String PHONE_REGEX = "^$|^(?=(?:\\D*\\d){7,15}\\D*$)[0-9 +\\-]+$";

  private Limits() {}
}
