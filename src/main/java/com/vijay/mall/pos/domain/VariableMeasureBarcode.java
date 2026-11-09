package com.vijay.mall.pos.domain;

import java.util.Optional;

/**
 * The standard "variable measure" EAN-13 printed by a weighing scale: two prefix digits (20 to 29), a 5-digit item code, a
 * 5-digit weight or price and the check digit.
 *
 * <p><b>Future.</b> The owner has no scale printer yet. The implementation is switched off by {@code barcode.variableMeasure.enabled=false}
 * and is only consulted by the counter scan when the setting is on. Until then the older shop setting (weighed prefix and PLU in
 * {@link BarcodeParser}) is the only weighed-label reader.
 */
public interface VariableMeasureBarcode {
  /** What a scale label carries: the item code (5 digits) and the weight in grams or the price in paise, as printed. */
  record Reading(String itemCode, int value) {}

  /** True when this reader is switched on. */
  boolean enabled();

  /**
   * Builds the 13 digits for an item code and a value.
   *
   * @param itemCode 1 to 5 digits (padded with zeros on the left)
   * @param value 0 to 99999
   * @throws IllegalStateException when the reader is switched off
   * @throws IllegalArgumentException for a bad item code or value
   */
  String generate(String itemCode, int value);

  /** Reads a scanned code; empty when the reader is off, the code is not a variable-measure code or its check digit is wrong. */
  Optional<Reading> parse(String code);
}
