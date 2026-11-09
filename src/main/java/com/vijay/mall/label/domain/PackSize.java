package com.vijay.mall.label.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a pack size such as {@code 500 g}, {@code 1.5 kg}, {@code 250ml}, {@code 2 L} or {@code 6 pcs} to price it per kg, litre or piece. */
public final class PackSize {
  private static final Pattern PATTERN = Pattern.compile("(?i)^\\s*(\\d+(?:[.,]\\d+)?)\\s*(kg|kgs|g|gm|gms|gram|grams|l|ltr|litre|liter|ml|pc|pcs|piece|pieces|nos)\\.?\\s*$");

  private PackSize() { }

  /**
   * The price of one kg, litre or piece, as text like {@code Rs 118.00/kg}.
   *
   * @param packSize the pack size text
   * @param pricePaise the price of the whole pack, in paise
   * @return empty when the pack size cannot be read or is zero
   */
  public static Optional<String> pricePerUnit(String packSize, long pricePaise) {
    if (packSize == null) return Optional.empty();
    Matcher m = PATTERN.matcher(packSize);
    if (!m.matches()) return Optional.empty();
    BigDecimal amount = new BigDecimal(m.group(1).replace(',', '.'));
    if (amount.signum() == 0) return Optional.empty();
    String unit = m.group(2).toLowerCase(Locale.ROOT);
    BigDecimal base;
    String label;
    switch (unit) {
      case "kg", "kgs" -> { base = amount; label = "kg"; }
      case "g", "gm", "gms", "gram", "grams" -> { base = amount.movePointLeft(3); label = "kg"; }
      case "l", "ltr", "litre", "liter" -> { base = amount; label = "L"; }
      case "ml" -> { base = amount.movePointLeft(3); label = "L"; }
      default -> { base = amount; label = "pc"; }
    }
    long perUnit = BigDecimal.valueOf(pricePaise).divide(base, 0, RoundingMode.HALF_UP).longValueExact();
    return Optional.of(LabelLayout.money(perUnit) + "/" + label);
  }
}
