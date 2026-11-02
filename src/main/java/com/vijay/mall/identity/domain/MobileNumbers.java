package com.vijay.mall.identity.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/** Pure helper: turns what a person typed into a 10-digit Indian mobile number, or nothing if it is not one. */
public final class MobileNumbers {
  private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");

  private MobileNumbers() {}

  /** Accepts 9876543210, +91 98765 43210, 09876543210, 91-9876543210 and similar. */
  public static Optional<String> normalize(String raw) {
    if (raw == null) return Optional.empty();
    String digits = raw.replaceAll("[\\s\\-()]", "");
    if (digits.startsWith("+91")) digits = digits.substring(3);
    else if (digits.startsWith("0091")) digits = digits.substring(4);
    else if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
    else if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
    return INDIAN_MOBILE.matcher(digits).matches() ? Optional.of(digits) : Optional.empty();
  }
}
