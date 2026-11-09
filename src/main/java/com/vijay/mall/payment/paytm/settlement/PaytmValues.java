package com.vijay.mall.payment.paytm.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** Reading the values Paytm's reports and APIs contain: rupee amounts, dates, payment modes and transaction types. */
final class PaytmValues {
  private PaytmValues() {}

  private static final List<DateTimeFormatter> DATE_TIMES = List.of(
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
      DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"), DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"),
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"), DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
      DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH), DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH));
  private static final List<DateTimeFormatter> DATES = List.of(
      DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("dd/MM/yyyy"),
      DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH));

  /**
   * A rupee amount such as {@code 1,234.50}, {@code Rs. 12}, {@code -5.00} or {@code (5.00)} in paise; null when blank.
   *
   * @throws IllegalArgumentException when it is not a number or has more than two decimals
   */
  static Long paise(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String s = raw.strip();
    boolean negative = s.startsWith("(") && s.endsWith(")");
    s = s.replaceAll("[()\\s,]|(?i)rs\\.?|INR|₹", "");
    try {
      BigDecimal rupees = new BigDecimal(s);
      if (rupees.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("amount has more than two decimals: " + raw);
      long paise = rupees.movePointRight(2).longValueExact();
      return negative ? -paise : paise;
    } catch (NumberFormatException | ArithmeticException e) {
      throw new IllegalArgumentException("not an amount: " + raw);
    }
  }

  /** A date and time in the formats Paytm uses (ISO with or without T and offset, dd-MM-yyyy, dd/MM/yyyy); a date alone is midnight. Null when blank. */
  static LocalDateTime dateTime(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String s = raw.strip();
    if (s.length() > 10 && s.charAt(10) == 'T') {
      try {
        return LocalDateTime.parse(s.replaceAll("(Z|[+-]\\d{2}:?\\d{2})$", "").replaceAll("\\.\\d+$", ""));
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("not a date: " + raw);
      }
    }
    s = s.replaceAll("\\.\\d+$", "");
    for (DateTimeFormatter f : DATE_TIMES) {
      try {
        return LocalDateTime.parse(s, f);
      } catch (DateTimeParseException ignored) {
        continue;
      }
    }
    for (DateTimeFormatter f : DATES) {
      try {
        return LocalDate.parse(s, f).atStartOfDay();
      } catch (DateTimeParseException ignored) {
        continue;
      }
    }
    throw new IllegalArgumentException("not a date: " + raw);
  }

  /** The date part of {@link #dateTime}; null when blank. */
  static LocalDate date(String raw) {
    LocalDateTime t = dateTime(raw);
    return t == null ? null : t.toLocalDate();
  }

  /** UPI, CARD, BALANCE, NETBANKING or OTHER. */
  static String mode(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String s = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    return switch (s) {
      case "UPI", "BHIMUPI" -> "UPI";
      case "CC", "DC", "CARD", "CREDITCARD", "DEBITCARD", "POS", "EMI" -> "CARD";
      case "BALANCE", "PPI", "WALLET", "PAYTMWALLET", "PPBL" -> "BALANCE";
      case "NB", "NETBANKING" -> "NETBANKING";
      default -> "OTHER";
    };
  }

  /** ACQUIRING (a sale), REFUND, CHARGEBACK or the raw upper-case value. */
  static String type(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String s = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    return switch (s) {
      case "ACQUIRING", "SALE", "PAYMENT", "ORDER" -> "ACQUIRING";
      case "REFUND" -> "REFUND";
      case "CHARGEBACK" -> "CHARGEBACK";
      default -> raw.strip().toUpperCase(Locale.ROOT);
    };
  }

  /** Keeps the first two and last two characters of a UPI address or card number: {@code ab***@ybl}. */
  static String maskVpa(String vpa) {
    if (vpa == null || vpa.isBlank()) return null;
    String v = vpa.strip();
    int at = v.indexOf('@');
    if (at < 0) return v.length() <= 2 ? "***" : v.substring(0, 2) + "***";
    return v.substring(0, Math.min(2, at)) + "***" + v.substring(at);
  }

  /** Null for blank text, otherwise the stripped text. */
  static String text(String raw) { return raw == null || raw.isBlank() ? null : raw.strip(); }
}
