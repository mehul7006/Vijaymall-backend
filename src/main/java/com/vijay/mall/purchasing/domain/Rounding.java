package com.vijay.mall.purchasing.domain;

import com.vijay.mall.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** How a per-unit cost is shown: exactly (4 decimals of a paisa), to the nearest paisa, or to the nearest rupee. */
public enum Rounding {
  /** The stored value with 4 decimals. */
  EXACT,
  /** Nearest whole paisa (half up). */
  PAISE,
  /** Nearest whole rupee (half up), still expressed in paise. */
  RUPEE;

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /**
   * Applies the rounding to an amount in paise.
   *
   * @param paise the exact amount (any scale)
   * @return the amount in paise, with scale 4 for EXACT and scale 0 otherwise
   */
  public BigDecimal apply(BigDecimal paise) {
    return switch (this) {
      case EXACT -> paise.setScale(4, RoundingMode.HALF_UP);
      case PAISE -> paise.setScale(0, RoundingMode.HALF_UP);
      case RUPEE -> paise.divide(HUNDRED).setScale(0, RoundingMode.HALF_UP).multiply(HUNDRED);
    };
  }

  /**
   * Reads the {@code rounding} request parameter.
   *
   * @param text the value (any case); null or blank means PAISE
   * @return the rounding
   * @throws ApiException 400 on field {@code rounding} for an unknown value
   */
  public static Rounding parse(String text) {
    if (text == null || text.isBlank()) return PAISE;
    try {
      return valueOf(text.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("rounding", "EXACT, PAISE or RUPEE");
    }
  }
}
