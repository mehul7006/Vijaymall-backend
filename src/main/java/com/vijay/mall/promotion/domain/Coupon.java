package com.vijay.mall.promotion.domain;

import com.vijay.mall.catalog.domain.PricedLine;
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
 *
 * productIds (single variants) and productGroupIds (every variant of one product) narrow the coupon to items. A cart line
 * is covered when its category is in categoryIds, or its product is in productIds, or its product group is in
 * productGroupIds. When all three lists are empty the coupon covers every line. At most {@link #MAX_ITEMS} products and
 * product groups together.
 */
public record Coupon(UUID id, String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                     UUID categoryId, int perUserLimit, int totalLimit, int usedCount, Instant startAt,
                     Instant endAt, boolean active, boolean worksWithCoins, boolean firstOrderOnly,
                     List<UUID> categoryIds, List<UUID> productIds, List<UUID> productGroupIds) {
  /** Most products and product groups one coupon may list together. */
  public static final int MAX_ITEMS = 200;
  /** Kind of a coupon whose value is a fixed amount in paise. */
  public static final String FLAT = "FLAT";
  /** Kind of a coupon whose value is a percentage of the covered amount. */
  public static final String PERCENT = "PERCENT";

  /**
   * Normalises the category list: nulls and duplicates are dropped and the single {@code categoryId} and the list are kept in step.
   */
  public Coupon {
    List<UUID> ids = categoryIds == null ? List.of()
        : categoryIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty() && categoryId != null) ids = List.of(categoryId);
    categoryIds = ids;
    categoryId = ids.isEmpty() ? null : ids.get(0);
    productIds = productIds == null ? List.of() : productIds.stream().filter(Objects::nonNull).distinct().toList();
    productGroupIds = productGroupIds == null ? List.of() : productGroupIds.stream().filter(Objects::nonNull).distinct().toList();
  }

  /** A coupon limited to categories only (no product or product group limit). */
  public Coupon(UUID id, String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                UUID categoryId, int perUserLimit, int totalLimit, int usedCount, Instant startAt,
                Instant endAt, boolean active, boolean worksWithCoins, boolean firstOrderOnly,
                List<UUID> categoryIds) {
    this(id, code, kind, value, minOrderPaise, maxDiscountPaise, categoryId, perUserLimit, totalLimit, usedCount,
        startAt, endAt, active, worksWithCoins, firstOrderOnly, categoryIds, List.of(), List.of());
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

  /** True when the coupon is limited to nothing at all: no category, product or product group (it covers every line). */
  public boolean unrestricted() { return categoryIds.isEmpty() && productIds.isEmpty() && productGroupIds.isEmpty(); }

  /** True when a cart line is covered: unrestricted, or its category, product or product group is listed. */
  public boolean covers(PricedLine line) {
    return unrestricted()
        || (line.categoryId() != null && categoryIds.contains(line.categoryId()))
        || productIds.contains(line.productId())
        || (line.productGroupId() != null && productGroupIds.contains(line.productGroupId()));
  }

  /** True when a line of this category gets the discount. A line without a category is never covered by a limited coupon. */
  public boolean covers(UUID lineCategoryId) {
    return categoryIds.isEmpty() || (lineCategoryId != null && categoryIds.contains(lineCategoryId));
  }

  /** The same coupon limited to these categories. */
  public Coupon withCategories(List<UUID> ids) {
    return new Coupon(id, code, kind, value, minOrderPaise, maxDiscountPaise, null, perUserLimit, totalLimit, usedCount,
        startAt, endAt, active, worksWithCoins, firstOrderOnly, ids, productIds, productGroupIds);
  }

  /** The same coupon limited to these products and product groups (and the categories it already has). */
  public Coupon withItems(List<UUID> products, List<UUID> groups) {
    return new Coupon(id, code, kind, value, minOrderPaise, maxDiscountPaise, categoryId, perUserLimit, totalLimit, usedCount,
        startAt, endAt, active, worksWithCoins, firstOrderOnly, categoryIds, products, groups);
  }

  /** True when customers may be shown this coupon at {@code now}: active, inside its dates and not used up. */
  public boolean offeredAt(Instant now) {
    boolean started = startAt == null || !now.isBefore(startAt);
    boolean notEnded = endAt == null || !now.isAfter(endAt);
    boolean hasUsesLeft = totalLimit <= 0 || usedCount < totalLimit;
    return active && started && notEnded && hasUsesLeft;
  }
}
