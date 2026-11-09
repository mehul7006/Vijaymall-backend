package com.vijay.mall.pos.app;

import com.vijay.mall.pos.domain.EanVariableMeasure;
import com.vijay.mall.pos.domain.VariableMeasureBarcode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the barcode settings into the objects that use them. */
@Configuration
class BarcodeConfig {
  /** The weighing-scale barcode reader; it does nothing unless {@code barcode.variableMeasure.enabled} is true. */
  @Bean
  VariableMeasureBarcode variableMeasureBarcode(BarcodeProps props) {
    return new EanVariableMeasure(props.variableMeasure().enabled(), props.variableMeasure().prefix());
  }
}
