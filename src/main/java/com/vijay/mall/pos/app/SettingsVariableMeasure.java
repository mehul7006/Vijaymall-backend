package com.vijay.mall.pos.app;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.pos.domain.EanVariableMeasure;
import com.vijay.mall.pos.domain.VariableMeasureBarcode;
import java.util.Optional;

/**
 * The weighing-scale barcode reader whose switch and prefix are read from the runtime settings on every call, so an admin
 * edit takes effect without a restart.
 */
public final class SettingsVariableMeasure implements VariableMeasureBarcode {
  private final RuntimeSettings settings;

  /** Creates the reader on top of the runtime settings. */
  public SettingsVariableMeasure(RuntimeSettings settings) { this.settings = settings; }

  /** The reader for the settings in force; a prefix that did not come through the admin screen and is not valid uses the default. */
  private EanVariableMeasure current() {
    String prefix = settings.get(Settings.BARCODE_VARIABLE_MEASURE_PREFIX).strip();
    if (!prefix.matches("2\\d")) prefix = Settings.BARCODE_VARIABLE_MEASURE_PREFIX.fallback();
    return new EanVariableMeasure(settings.getBool(Settings.BARCODE_VARIABLE_MEASURE_ENABLED), prefix);
  }

  @Override public boolean enabled() { return settings.getBool(Settings.BARCODE_VARIABLE_MEASURE_ENABLED); }

  @Override public String generate(String itemCode, int value) { return current().generate(itemCode, value); }

  @Override public Optional<Reading> parse(String code) { return current().parse(code); }
}
