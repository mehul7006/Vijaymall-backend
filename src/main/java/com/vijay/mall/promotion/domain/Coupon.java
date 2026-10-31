package com.vijay.mall.promotion.domain;

import java.time.Instant;
import java.util.UUID;

/** value is paise for FLAT and a percent for PERCENT. Zero limits mean "no limit". */
public record Coupon(UUID id, String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                     UUID categoryId, int perUserLimit, int totalLimit, int usedCount, Instant startAt,
                     Instant endAt, boolean active) {
  public static final String FLAT = "FLAT";
  public static final String PERCENT = "PERCENT";
}
