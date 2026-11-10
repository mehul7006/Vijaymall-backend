package com.vijay.mall.gst.app;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Whether the billing engine can store a GST rate today, the one place that answers {@code acceptedForBilling}.
 *
 * <p><b>Swap point.</b> Until {@code gstPercent} becomes a decimal in the POS and purchasing code, billing stores only the six
 * whole-number rates 0, 3, 5, 12, 18 and 28, so 40, 1.5 and 0.25 are suggested but marked not accepted. When
 * {@code pos/domain/GstRates} (allowed list 0, 0.25, 1.5, 3, 5, 12, 18, 28, 40 with {@code accepts(BigDecimal)}) exists, replace
 * the body of {@link #accepts} with {@code GstRates.accepts(rate)} and delete {@link #WHOLE_RATES}; nothing else in the gst module
 * knows the list.
 */
final class BillingRateRule {
  /** The rates the billing engine can store before the decimal change. */
  static final Set<Integer> WHOLE_RATES = Set.of(0, 3, 5, 12, 18, 28);

  private BillingRateRule() {}

  /** True when {@code rate} (percent, any scale) can be stored on a product or a bill line today. */
  static boolean accepts(BigDecimal rate) {
    if (rate == null) return false;
    BigDecimal plain = rate.stripTrailingZeros();
    return plain.scale() <= 0 && plain.compareTo(BigDecimal.valueOf(100)) <= 0 && WHOLE_RATES.contains(plain.intValueExact());
  }
}
