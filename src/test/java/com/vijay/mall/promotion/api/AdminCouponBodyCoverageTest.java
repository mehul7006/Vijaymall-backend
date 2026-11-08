package com.vijay.mall.promotion.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.promotion.domain.Coupon;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminCouponBodyCoverageTest {
  private static final Instant START = Instant.parse("2027-01-01T00:00:00Z");
  private static final Instant END = Instant.parse("2027-02-01T00:00:00Z");

  @Test
  void theOlderConstructorWithoutCategoryIdsKeepsTheSingleCategory() {
    var cat = UUID.randomUUID();
    var body = new AdminCouponController.Body("save", "FLAT", 100L, 500L, 50L, cat, 2, 10, START, END, false, false, true);
    assertThat(body.categoryIds()).isNull();

    Coupon c = body.toCoupon();
    assertThat(c.id()).isNull();
    assertThat(c.code()).isEqualTo("save");
    assertThat(c.kind()).isEqualTo("FLAT");
    assertThat(c.value()).isEqualTo(100);
    assertThat(c.minOrderPaise()).isEqualTo(500);
    assertThat(c.maxDiscountPaise()).isEqualTo(50);
    assertThat(c.perUserLimit()).isEqualTo(2);
    assertThat(c.totalLimit()).isEqualTo(10);
    assertThat(c.usedCount()).isZero();
    assertThat(c.startAt()).isEqualTo(START);
    assertThat(c.endAt()).isEqualTo(END);
    assertThat(c.active()).isFalse();
    assertThat(c.worksWithCoins()).isFalse();
    assertThat(c.firstOrderOnly()).isTrue();
    assertThat(c.categoryIds()).containsExactly(cat);
  }

  @Test
  void missingOptionalFieldsTakeTheirDefaults() {
    var body = new AdminCouponController.Body("x", "PERCENT", 10L, null, null, null, null, null, null, null, null, null, null);
    Coupon c = body.toCoupon();
    assertThat(c.minOrderPaise()).isZero();
    assertThat(c.maxDiscountPaise()).isZero();
    assertThat(c.perUserLimit()).isZero();
    assertThat(c.totalLimit()).isZero();
    assertThat(c.active()).isTrue();
    assertThat(c.worksWithCoins()).isTrue();
    assertThat(c.firstOrderOnly()).isFalse();
    assertThat(c.allCategories()).isTrue();
    assertThat(c.categoryId()).isNull();
  }

  @Test
  void explicitTrueFlagsAndSeveralCategoriesAreKept() {
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    var body = new AdminCouponController.Body("x", "FLAT", 10L, null, null, null, null, null, null, null, true, true, false, List.of(a, b));
    Coupon c = body.toCoupon();
    assertThat(c.active()).isTrue();
    assertThat(c.worksWithCoins()).isTrue();
    assertThat(c.firstOrderOnly()).isFalse();
    assertThat(c.categoryIds()).containsExactly(a, b);
    assertThat(c.categoryId()).isEqualTo(a);
  }
}
