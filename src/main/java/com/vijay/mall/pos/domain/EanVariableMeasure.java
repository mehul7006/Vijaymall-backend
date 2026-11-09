package com.vijay.mall.pos.domain;

import java.util.Optional;

/**
 * Variable-measure EAN-13 with a two-digit prefix: {@code PP IIIII VVVVV C}. Disabled unless built with {@code enabled = true}.
 */
public final class EanVariableMeasure implements VariableMeasureBarcode {
  private final boolean enabled;
  private final String prefix;

  /**
   * Creates the reader.
   *
   * @param enabled the {@code barcode.variableMeasure.enabled} setting
   * @param prefix the two-digit prefix, 20 to 29
   * @throws IllegalArgumentException when the prefix is not two digits from 20 to 29
   */
  public EanVariableMeasure(boolean enabled, String prefix) {
    if (prefix == null || !prefix.matches("2\\d")) throw new IllegalArgumentException("variable-measure prefix must be 20 to 29");
    this.enabled = enabled;
    this.prefix = prefix;
  }

  @Override public boolean enabled() { return enabled; }

  @Override
  public String generate(String itemCode, int value) {
    if (!enabled) throw new IllegalStateException("variable-measure barcodes are switched off");
    if (itemCode == null || !itemCode.matches("\\d{1,5}")) throw new IllegalArgumentException("item code must be 1 to 5 digits");
    if (value < 0 || value > 99_999) throw new IllegalArgumentException("value must be 0 to 99999");
    return Gtin.withCheckDigit(prefix + "0".repeat(5 - itemCode.length()) + itemCode + "0".repeat(5 - Integer.toString(value).length()) + value);
  }

  @Override
  public Optional<Reading> parse(String code) {
    if (!enabled || code == null || !code.matches("\\d{13}") || !code.startsWith(prefix) || !Gtin.isValid(code)) return Optional.empty();
    return Optional.of(new Reading(code.substring(2, 7), Integer.parseInt(code.substring(7, 12))));
  }
}
