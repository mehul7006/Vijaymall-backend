package com.vijay.mall.label.app;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The default {@link LabelLotSource}: there are no lots yet, so nothing is filled in. */
@Component
class NoLabelLots implements LabelLotSource {
  @Override public Optional<LotInfo> lotFor(UUID variantId) { return Optional.empty(); }
}
