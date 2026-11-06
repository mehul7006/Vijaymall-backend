package com.vijay.mall.promotion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * value is paise for FLAT and a percent for PERCENT. Zero limits mean "no limit".
 * worksWithCoins false means coins cannot be spent in the same order; firstOrderOnly means the customer must have
 * no earlier (non-cancelled) order.
 *
 * categoryIds are the categories the coupon covers; empty means every category. Only the lines of those categories
 * are discounted, even when the cart holds other categories too. categoryId is kept for older clients and is always
 * the first of categoryIds (or null); either one may be given and the other is filled in.
 */
public record Coupon(UUID id, String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                     UUID categoryId, int perUserLimit, int totalLimit, int usedCount, Instant startAt,
                     Instant endAt, boolean active, boolean worksWithCoins, boolean firstOrderOnly,
                     List<UUID> categoryIds) {
  public static final String FLAT = "FLAT";
  public static final String PERCENT = "PERCENT";

  public Coupon {
    List<UUID> ids = categoryIds == null ? List.of()
        : categoryIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty() && categoryId != null) ids = List.of(categoryId);
    categoryIds = ids;
    categoryId = ids.isEmpty() ? null : ids.get(0);
  }

  /** A coupon limited to one category (or none), as before categories became a list. */
  public Coupon(UUID id, String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                UUID categoryId, int perUserLimit, int totalLimit, int usedCount, Instant startAt,
                Instant endAt, boolean active, boolean worksWithCoins, boolean firstOrderOnly) {
    this(id, code, kind, value, minOrderPaise, maxDiscountPaise, categoryId, perUserLimit, totalLimit, usedCount,
        startAt, endAt, active, worksWithCoins, firstOrderOnly, List.of());
  }

  /** A coupon that works with coins and is not limited to the first order. */
  public Coupon(UUID id, String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                UUID categoryId, int perUserLimit, int totalLimit, int usedCount, Instant startAt,
                Instant endAt, boolean active) {
    this(id, code, kind, value, minOrderPaise, maxDiscountPaise, categoryId, perUserLimit, totalLimit, usedCount,
        startAt, endAt, active, true, false);
  }

  /** True when the coupon is not limited to particular categories. */
  public boolean allCategories() { return categoryIds.isEmpty(); }

  /** True when a line of this category gets the discount. A line without a category is never covered by a limited coupon. */
  public boolean covers(UUID lineCategoryId) {
    return categoryIds.isEmpty() || (lineCategoryId != null && categoryIds.contains(lineCategoryId));
  }

  /** The same coupon limited to these categories. */
  public Coupon withCategories(List<UUID> ids) {
    return new Coupon(id, code, kind, value, minOrderPaise, maxDiscountPaise, null, perUserLimit, totalLimit, usedCount,
        startAt, endAt, active, worksWithCoins, firstOrderOnly, ids);
  }

  /** True when customers may be shown this coupon at {@code now}: active, inside its dates and not used up. */
  public boolean offeredAt(Instant now) {
    boolean started = startAt == null || !now.isBefore(startAt);
    boolean notEnded = endAt == null || !now.isAfter(endAt);
    boolean hasUsesLeft = totalLimit <= 0 || usedCount < totalLimit;
    return active && started && notEnded && hasUsesLeft;
  }
}
