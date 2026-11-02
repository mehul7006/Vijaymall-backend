package com.vijay.mall.promotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.promotion.domain.CouponContext;
import com.vijay.mall.promotion.domain.CouponRules;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CouponFlagsTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");

  private static Coupon coupon(boolean firstOrderOnly) {
    return new Coupon(UUID.randomUUID(), "X", Coupon.FLAT, 100, 0, 0, null, 0, 0, 0, null, null, true, true, firstOrderOnly);
  }

  private static Optional<String> check(Coupon c, int priorOrders) {
    return CouponRules.firstViolation(CouponRules.STANDARD, new CouponContext(c, NOW, 1000, List.of(), 0, priorOrders));
  }

  @Test
  void firstOrderOnlyCouponWorksForACustomerWithNoEarlierOrder() {
    assertEquals(Optional.empty(), check(coupon(true), 0));
  }

  @Test
  void firstOrderOnlyCouponIsRefusedOnceThereIsAnEarlierOrder() {
    assertEquals(Optional.of("FIRST_ORDER_ONLY"), check(coupon(true), 1));
  }

  @Test
  void ordinaryCouponIgnoresEarlierOrders() {
    assertEquals(Optional.empty(), check(coupon(false), 7));
  }

  @Test
  void couponsBuiltTheOldWayWorkWithCoinsAndAreNotFirstOrderOnly() {
    Coupon old = new Coupon(UUID.randomUUID(), "X", Coupon.FLAT, 100, 0, 0, null, 0, 0, 0, null, null, true);
    assertTrue(old.worksWithCoins());
    assertFalse(old.firstOrderOnly());
  }

  @Test
  void contextBuiltTheOldWayHasNoPriorOrders() {
    assertEquals(0, new CouponContext(coupon(true), NOW, 1000, List.of(), 0).priorOrders());
  }
}
