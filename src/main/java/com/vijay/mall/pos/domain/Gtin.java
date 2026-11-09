package com.vijay.mall.pos.domain;

import java.util.Optional;

/**
 * Check-digit rules for the numeric retail barcodes (EAN-13, EAN-8 and UPC-A) and helpers to build store-internal EAN-13 codes.
 * Other lengths and text codes (Code 128) have no check digit that this class judges.
 */
public final class Gtin {
  private Gtin() { }

  /** True when the text is exactly 8, 12 or 13 digits, the lengths of EAN-8, UPC-A and EAN-13. */
  public static boolean isStandardLength(String code) {
    return code != null && code.matches("\\d{8}|\\d{12}|\\d{13}");
  }

  /** The check digit (0 to 9) for the digits that come before it; the body is all digits and may be any length. */
  public static int checkDigit(String body) {
    int sum = 0;
    for (int i = 0; i < body.length(); i++) {
      int digit = body.charAt(body.length() - 1 - i) - '0';
      sum += digit * (i % 2 == 0 ? 3 : 1);
    }
    return (10 - sum % 10) % 10;
  }

  /** True for an EAN-13, EAN-8 or UPC-A whose last digit is the right check digit; false for anything else. */
  public static boolean isValid(String code) {
    return isStandardLength(code) && checkDigit(code.substring(0, code.length() - 1)) == code.charAt(code.length() - 1) - '0';
  }

  /** The body with its check digit appended, for example {@code withCheckDigit("400638133393")} gives {@code 4006381333931}. */
  public static String withCheckDigit(String body) { return body + checkDigit(body); }

  /**
   * Explains a wrong check digit in plain words, or returns empty when the code is fine or is not a standard length (so it is
   * not judged).
   */
  public static Optional<String> checkDigitProblem(String code) {
    if (!isStandardLength(code) || isValid(code)) return Optional.empty();
    int right = checkDigit(code.substring(0, code.length() - 1));
    return Optional.of("barcode " + code + " has a wrong last digit: for these digits it should be " + right
        + ". Check that the code was typed or scanned correctly");
  }
}
