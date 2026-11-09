package com.vijay.mall.label.app;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a label gets its packing date, best-before date and batch number when the print request does not give them.
 *
 * <p>Extension point for the stock-lot work: a lot-aware implementation (marked {@code @Primary}) answers with the lot that will be
 * sold next. Until then {@link NoLabelLots} answers nothing and the values come from the request only.
 */
public interface LabelLotSource {
  /** The dates and batch of a lot; any part may be null. */
  record LotInfo(LocalDate packedOn, LocalDate bestBefore, String batchNo) {}

  /** The lot information for a variant, or empty. */
  Optional<LotInfo> lotFor(UUID variantId);
}
