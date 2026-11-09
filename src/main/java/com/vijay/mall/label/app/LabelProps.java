package com.vijay.mall.label.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Label printing settings, bound from the {@code label} prefix.
 *
 * @param dpi dots per inch of the thermal printer (203 is the usual office label printer, 300 the finer one); 150 to 600
 */
@ConfigurationProperties(prefix = "label")
public record LabelProps(@DefaultValue("203") int dpi) {
  /** Refuses a resolution no label printer has. */
  public LabelProps {
    if (dpi < 150 || dpi > 600) throw new IllegalArgumentException("label.dpi must be 150 to 600");
  }
}
