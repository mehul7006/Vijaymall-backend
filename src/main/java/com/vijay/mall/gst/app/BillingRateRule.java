package com.vijay.mall.gst.app;

import com.vijay.mall.common.tax.GstRates;
import java.math.BigDecimal;

/**
 * Whether the billing engine can store a GST rate, the one place that answers {@code acceptedForBilling}. Billing stores every rate
 * of {@link GstRates#ALLOWED} (0, 0.25, 1.5, 3, 5, 12, 18, 28 and 40); nothing else in the gst module knows the list.
 */
final class BillingRateRule {
  private BillingRateRule() {}

  /** True when {@code rate} (percent, any scale) can be stored on a product or a bill line. */
  static boolean accepts(BigDecimal rate) {
    return GstRates.accepts(rate);
  }
}
