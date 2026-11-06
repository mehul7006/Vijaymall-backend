package com.vijay.mall.common.live;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Published inside the transaction that moves stock; the live stream sends it after commit so screens refresh at once. */
public record StockChanged(List<UUID> productIds, String reason, Instant at) {
  public StockChanged { productIds = productIds == null ? List.of() : List.copyOf(productIds); }
}
