package com.vijay.mall.promotion.domain;

import java.time.Instant;
import java.util.UUID;

/** value is paise for FLAT and a percent for PERCENT. Zero limits mean "no limit". */
public record Coupon(UUID id, String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                     UUID categoryId, int perUserLimit, int totalLimit, int usedCount, Instant startAt,
                     Instant endAt, boolean active) {
  public static final String FLAT = "FLAT";
  public static final String PERCENT = "PERCENT";

  /** True when customers may be shown this coupon at {@code now}: active, inside its dates and not used up. */
  public boolean offeredAt(Instant now) {
    boolean started = startAt == null || !now.isBefore(startAt);
    boolean notEnded = endAt == null || !now.isAfter(endAt);
    boolean hasUsesLeft = totalLimit <= 0 || usedCount < totalLimit;
    return active && started && notEnded && hasUsesLeft;
  }
}
