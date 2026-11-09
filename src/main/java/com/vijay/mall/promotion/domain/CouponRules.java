package com.vijay.mall.promotion.domain;

import java.util.List;
import java.util.Optional;

/** The standard rule set, checked in order. Add a rule here to add a new coupon condition. */
public final class CouponRules {
  private CouponRules() {}

  private static Optional<String> violation(boolean broken, String code) {
    return broken ? Optional.of(code) : Optional.empty();
  }

  /**
   * Why no line of the cart is covered: {@code ITEM} for a coupon that lists products or product groups, {@code CATEGORY}
   * for a category coupon, {@code FLASH_ITEMS} when the lines it is for are all on a flash sale the coupon may not touch.
   * The value is only used when no line is covered.
   */
  /** A coupon for everything is not refused for an empty cart; a restricted one, or one that leaves out flash lines, can be. */
  private static boolean mayBeUncovered(CouponContext c) {
    return !c.coupon().unrestricted() || (!c.allowOnFlash() && !c.lines().isEmpty());
  }

  static String uncoveredReason(CouponContext c) {
    Coupon x = c.coupon();
    if (c.lines().stream().anyMatch(x::covers)) return "FLASH_ITEMS";
    return x.productIds().isEmpty() && x.productGroupIds().isEmpty() ? "CATEGORY" : "ITEM";
  }

  /**
   * The coupon conditions in the order they are checked; the first one broken is the reason shown. The reason codes are
   * {@code EXPIRED}, {@code MIN_ORDER}, {@code CATEGORY}, {@code ITEM}, {@code FLASH_ITEMS}, {@code LIMIT_REACHED} and {@code FIRST_ORDER_ONLY}.
   */
  public static final List<CouponRule> STANDARD = List.of(
      c -> violation(!c.coupon().active(), "EXPIRED"),
      c -> {
        var x = c.coupon();
        boolean before = x.startAt() != null && c.now().isBefore(x.startAt());
        boolean after = x.endAt() != null && c.now().isAfter(x.endAt());
        return violation(before || after, "EXPIRED");
      },
      c -> violation(c.subtotalPaise() < c.coupon().minOrderPaise(), "MIN_ORDER"),
      c -> mayBeUncovered(c) && c.lines().stream().noneMatch(c::covered) ? Optional.of(uncoveredReason(c)) : Optional.<String>empty(),
      /**
       * The minimum counts only what is spent on the lines the coupon covers (its categories and items, and not flash sale
       * lines when the admin excluded them), not the whole cart.
       */
      c -> violation(c.coveredPaise() < c.coupon().minOrderPaise(), "MIN_ORDER"),
      c -> violation(c.coupon().perUserLimit() > 0 && c.userUses() >= c.coupon().perUserLimit(), "LIMIT_REACHED"),
      c -> violation(c.coupon().totalLimit() > 0 && c.coupon().usedCount() >= c.coupon().totalLimit(), "LIMIT_REACHED"),
      c -> violation(c.coupon().firstOrderOnly() && c.priorOrders() > 0, "FIRST_ORDER_ONLY"));

  /** First violation, if any. Rules are evaluated lazily, so later rules do not run once one fails. */
  public static Optional<String> firstViolation(List<CouponRule> rules, CouponContext ctx) {
    return rules.stream().map(r -> r.violation(ctx)).flatMap(Optional::stream).findFirst();
  }
}
