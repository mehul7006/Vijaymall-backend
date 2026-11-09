package com.vijay.mall.promotion.domain;

import java.util.List;
import java.util.Optional;

/** The standard rule set, checked in order. Add a rule here to add a new coupon condition. */
public final class CouponRules {
  private CouponRules() {}

  private static Optional<String> violation(boolean broken, String code) {
    return broken ? Optional.of(code) : Optional.empty();
  }

  public static final List<CouponRule> STANDARD = List.of(
      c -> violation(!c.coupon().active(), "EXPIRED"),
      c -> {
        var x = c.coupon();
        boolean before = x.startAt() != null && c.now().isBefore(x.startAt());
        boolean after = x.endAt() != null && c.now().isAfter(x.endAt());
        return violation(before || after, "EXPIRED");
      },
      c -> violation(c.subtotalPaise() < c.coupon().minOrderPaise(), "MIN_ORDER"),
      c -> violation(!c.coupon().allCategories()
          && c.lines().stream().noneMatch(l -> c.coupon().covers(l.categoryId())), "CATEGORY"),
      /**
       * A category coupon's minimum counts only what is spent in its categories, not the whole cart.
       */
      c -> violation(!c.coupon().allCategories()
          && CouponMath.base(c.coupon(), c.lines(), c.subtotalPaise()) < c.coupon().minOrderPaise(), "MIN_ORDER"),
      c -> violation(c.coupon().perUserLimit() > 0 && c.userUses() >= c.coupon().perUserLimit(), "LIMIT_REACHED"),
      c -> violation(c.coupon().totalLimit() > 0 && c.coupon().usedCount() >= c.coupon().totalLimit(), "LIMIT_REACHED"),
      c -> violation(c.coupon().firstOrderOnly() && c.priorOrders() > 0, "FIRST_ORDER_ONLY"));

  /** First violation, if any. Rules are evaluated lazily, so later rules do not run once one fails. */
  public static Optional<String> firstViolation(List<CouponRule> rules, CouponContext ctx) {
    return rules.stream().map(r -> r.violation(ctx)).flatMap(Optional::stream).findFirst();
  }
}
