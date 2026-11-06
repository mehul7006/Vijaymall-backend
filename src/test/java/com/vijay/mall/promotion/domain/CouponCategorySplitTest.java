package com.vijay.mall.promotion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.catalog.domain.PricedLine;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CouponCategorySplitTest {
  private final UUID dairy = UUID.randomUUID();
  private final UUID snacks = UUID.randomUUID();
  private final List<PricedLine> cart = List.of(
      new PricedLine(UUID.randomUUID(), dairy, "Milk", 1, 3333, 3333, true, true),
      new PricedLine(UUID.randomUUID(), snacks, "Chips", 1, 5000, 5000, true, true),
      new PricedLine(UUID.randomUUID(), dairy, "Curd", 1, 6667, 6667, true, true));

  private Coupon coupon(String kind, long value, long min, UUID... categories) {
    return new Coupon(UUID.randomUUID(), "C", kind, value, min, 0, null, 0, 0, 0, null, null, true, true, false,
        List.of(categories));
  }

  @Test
  void aCategoryCouponDiscountsOnlyItsOwnLinesInAMixedCart() {
    Coupon c = coupon(Coupon.PERCENT, 10, 0, dairy);
    long base = CouponMath.base(c, cart, 15000);
    long discount = CouponMath.discount(c, base);
    assertThat(base).isEqualTo(10000);
    assertThat(discount).isEqualTo(1000);
    assertThat(CouponMath.perLine(c, cart, discount)).containsExactly(333, 0, 667);
  }

  @Test
  void aCouponForSeveralCategoriesCoversEachOfThem() {
    Coupon c = coupon(Coupon.FLAT, 500, 0, dairy, snacks);
    assertThat(CouponMath.base(c, cart, 15000)).isEqualTo(15000);
    assertThat(Arrays.stream(CouponMath.perLine(c, cart, 500)).sum()).isEqualTo(500);
  }

  @Test
  void aCouponWithoutCategoriesSharesOverEveryLineAndSumsExactly() {
    long[] shares = CouponMath.perLine(coupon(Coupon.FLAT, 1000, 0), cart, 1000);
    assertThat(shares).containsExactly(222, 333, 445);
  }

  @Test
  void theDiscountNeverExceedsWhatTheCoveredLinesCost() {
    Coupon c = coupon(Coupon.FLAT, 99999, 0, snacks);
    long discount = CouponMath.discount(c, CouponMath.base(c, cart, 15000));
    assertThat(discount).isEqualTo(5000);
    assertThat(CouponMath.perLine(c, cart, 99999)).containsExactly(0, 5000, 0);
  }

  @Test
  void aSingleCategoryIdKeepsWorkingAndFillsTheList() {
    Coupon c = new Coupon(UUID.randomUUID(), "C", Coupon.FLAT, 100, 0, 0, dairy, 0, 0, 0, null, null, true);
    assertThat(c.categoryIds()).containsExactly(dairy);
    assertThat(c.covers(dairy)).isTrue();
    assertThat(c.covers(snacks)).isFalse();
    assertThat(c.covers(null)).isFalse();
    assertThat(coupon(Coupon.FLAT, 1, 0).covers(null)).isTrue();
  }

  @Test
  void allocateGivesEarlierLinesTheLeftoverPaiseOnTies() {
    assertThat(CouponMath.allocate(1, new long[] {1, 1, 1})).containsExactly(1, 0, 0);
  }
}
