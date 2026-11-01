package com.vijay.mall.promotion.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * value is paise for FLAT and a percent for PERCENT. Zero limits mean "no limit".
 * worksWithCoins false means coins cannot be spent in the same order; firstOrderOnly means the customer must have
 * no earlier (non-cancelled) order.
 */
public record Coupon(UUID id, String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                     UUID categoryId, int perUserLimit, int totalLimit, int usedCount, Instant startAt,
                     Instant endAt, boolean active, boolean worksWithCoins, boolean firstOrderOnly) {
  public static final String FLAT = "FLAT";
  public static final String PERCENT = "PERCENT";

  /** A coupon that works with coins and is not limited to the first order. */
  public Coupon(UUID id, String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                UUID categoryId, int perUserLimit, int totalLimit, int usedCount, Instant startAt,
                Instant endAt, boolean active) {
    this(id, code, kind, value, minOrderPaise, maxDiscountPaise, categoryId, perUserLimit, totalLimit, usedCount,
        startAt, endAt, active, true, false);
  }
}
