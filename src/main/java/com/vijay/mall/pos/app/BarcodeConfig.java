package com.vijay.mall.pos.app;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.pos.domain.VariableMeasureBarcode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the barcode settings into the objects that use them. */
@Configuration
class BarcodeConfig {
  /** The weighing-scale barcode reader; it does nothing unless the setting {@code barcode.variableMeasure.enabled} is on. */
  @Bean
  VariableMeasureBarcode variableMeasureBarcode(RuntimeSettings settings) { return new SettingsVariableMeasure(settings); }
}
