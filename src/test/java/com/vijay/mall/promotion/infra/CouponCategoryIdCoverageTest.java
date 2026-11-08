package com.vijay.mall.promotion.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The JPA composite key of the coupon/category link: value equality on both parts. */
class CouponCategoryIdCoverageTest {
  private final UUID coupon = UUID.randomUUID();
  private final UUID category = UUID.randomUUID();

  @Test
  void keysWithTheSamePartsAreEqualWithEqualHashes() {
    var a = new CouponCategoryId(coupon, category);
    var b = new CouponCategoryId(coupon, category);
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isEqualTo(a);
    assertThat(a.couponId).isEqualTo(coupon);
    assertThat(a.categoryId).isEqualTo(category);
  }

  @Test
  void keysDifferWhenEitherPartDiffers() {
    var a = new CouponCategoryId(coupon, category);
    assertThat(a).isNotEqualTo(new CouponCategoryId(UUID.randomUUID(), category));
    assertThat(a).isNotEqualTo(new CouponCategoryId(coupon, UUID.randomUUID()));
  }

  @Test
  void otherTypesAndNullAreNotEqual() {
    var a = new CouponCategoryId(coupon, category);
    assertThat(a.equals(null)).isFalse();
    assertThat(a.equals("x")).isFalse();
  }

  @Test
  void theNoArgKeyHasNullPartsAndStillWorks() {
    var empty = new CouponCategoryId();
    assertThat(empty).isEqualTo(new CouponCategoryId()).hasSameHashCodeAs(new CouponCategoryId());
    assertThat(empty).isNotEqualTo(new CouponCategoryId(coupon, category));
  }
}
