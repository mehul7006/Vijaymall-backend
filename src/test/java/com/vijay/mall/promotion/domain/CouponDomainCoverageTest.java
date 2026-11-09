package com.vijay.mall.promotion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.catalog.domain.PricedLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CouponDomainCoverageTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
  private static final UUID DAIRY = UUID.randomUUID();
  private static final UUID SNACKS = UUID.randomUUID();

  private static PricedLine line(UUID cat, long total) {
    return new PricedLine(UUID.randomUUID(), cat, "p", 1, total, total, true, true);
  }

  private static Coupon coupon(long min, List<UUID> cats) {
    return new Coupon(UUID.randomUUID(), "C", Coupon.FLAT, 100, min, 0, null, 0, 0, 0, null, null, true, true, false, cats);
  }

  @Test
  void withCategoriesReplacesTheCategoriesAndKeepsTheRest() {
    var original = new Coupon(UUID.randomUUID(), "C", Coupon.PERCENT, 10, 500, 200, DAIRY, 2, 9, 3, NOW, NOW.plusSeconds(60),
        true, false, true, List.of(DAIRY));
    var changed = original.withCategories(List.of(SNACKS, DAIRY));
    assertThat(changed.categoryIds()).containsExactly(SNACKS, DAIRY);
    assertThat(changed.categoryId()).isEqualTo(SNACKS); // the single id follows the first of the list
    assertThat(changed.id()).isEqualTo(original.id());
    assertThat(changed.code()).isEqualTo("C");
    assertThat(changed.kind()).isEqualTo(Coupon.PERCENT);
    assertThat(changed.minOrderPaise()).isEqualTo(500);
    assertThat(changed.maxDiscountPaise()).isEqualTo(200);
    assertThat(changed.perUserLimit()).isEqualTo(2);
    assertThat(changed.totalLimit()).isEqualTo(9);
    assertThat(changed.usedCount()).isEqualTo(3);
    assertThat(changed.worksWithCoins()).isFalse();
    assertThat(changed.firstOrderOnly()).isTrue();
    assertThat(original.withCategories(List.of()).allCategories()).isTrue();
  }

  @Test
  void perLineIsAllZeroWhenNothingIsCoveredOrTheDiscountIsZero() {
    var cart = List.of(line(SNACKS, 1000), line(SNACKS, 500));
    assertThat(CouponMath.perLine(coupon(0, List.of(DAIRY)), cart, 300)).containsExactly(0, 0);
    assertThat(CouponMath.perLine(coupon(0, List.of()), cart, 0)).containsExactly(0, 0);
    assertThat(CouponMath.perLine(coupon(0, List.of()), List.of(), 100)).isEmpty();
  }

  @Test
  void publicCouponFromASingleCategoryOrNone() {
    var none = new PublicCoupon("A", "FLAT", 100, 0, 0, null, null, null, true, false, 0);
    assertThat(none.categoryIds()).isEmpty();
    var one = new PublicCoupon("A", "FLAT", 100, 0, 0, DAIRY, null, null, true, false, 0);
    assertThat(one.categoryIds()).containsExactly(DAIRY);
  }

  @Test
  void categoryCouponMinimumCountsOnlyItsOwnCategories() {
    var c = coupon(1000, List.of(DAIRY));
    var cart = List.of(line(DAIRY, 400), line(SNACKS, 5000)); // whole cart 5400, dairy only 400
    var ctx = new CouponContext(c, NOW, 5400, cart, 0);
    assertThat(CouponRules.firstViolation(CouponRules.STANDARD, ctx)).contains("MIN_ORDER");

    var enough = new CouponContext(c, NOW, 6000, List.of(line(DAIRY, 1000), line(SNACKS, 5000)), 0);
    assertThat(CouponRules.firstViolation(CouponRules.STANDARD, enough)).isEmpty();
  }

  @Test
  void noCategoryLineIsNeverCoveredByALimitedCoupon() {
    var c = coupon(0, List.of(DAIRY));
    assertThat(c.covers((java.util.UUID) null)).isFalse();
    assertThat(c.covers(DAIRY)).isTrue();
    assertThat(CouponRules.firstViolation(CouponRules.STANDARD,
        new CouponContext(c, NOW, 100, List.of(line(null, 100)), 0))).contains("CATEGORY");
  }
}
