package com.vijay.mall.pos.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Barcode settings, bound from the {@code barcode} prefix.
 *
 * @param storePrefix first digits of generated store-internal EAN-13 codes; 1 to 4 digits starting with 2 (the GS1 range for
 *     in-store use is 20 to 29). The default 29 keeps clear of the scale prefix 2 that weighed labels use
 * @param variableMeasure the weighing-scale barcode reader, switched off by default
 */
@ConfigurationProperties(prefix = "barcode")
public record BarcodeProps(@DefaultValue("29") String storePrefix, @DefaultValue VariableMeasure variableMeasure) {
  /**
   * The variable-measure (scale printer) settings.
   *
   * @param enabled {@code barcode.variableMeasure.enabled}, false until the shop has a scale printer
   * @param prefix two-digit prefix of those labels, 20 to 29
   */
  public record VariableMeasure(@DefaultValue("false") boolean enabled, @DefaultValue("22") String prefix) {}

  /** Refuses a store prefix outside the 2 range, so the app does not start with a setting that would make wrong barcodes. */
  public BarcodeProps {
    if (storePrefix == null || !storePrefix.matches("2\\d{0,3}")) {
      throw new IllegalArgumentException("barcode.storePrefix must be 1 to 4 digits starting with 2 (GS1 in-store range 20 to 29)");
    }
  }
}
