package com.vijay.mall.promotion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.promotion.domain.CouponContext;
import com.vijay.mall.promotion.domain.CouponMath;
import com.vijay.mall.promotion.domain.CouponRules;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CouponRulesTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final UUID DAIRY = UUID.randomUUID();
  private static final UUID SNACKS = UUID.randomUUID();

  private static Coupon coupon(String kind, long value, long min, long max, UUID cat, int perUser, int total, int used,
                               Instant start, Instant end, boolean active) {
    return new Coupon(UUID.randomUUID(), "X", kind, value, min, max, cat, perUser, total, used, start, end, active);
  }

  private static Coupon flat(long value, long min) {
    return coupon(Coupon.FLAT, value, min, 0, null, 0, 0, 0, null, null, true);
  }

  private static PricedLine line(UUID cat, long total) {
    return new PricedLine(UUID.randomUUID(), cat, "p", 1, total, total, true, true);
  }

  private static Optional<String> check(Coupon c, long subtotal, List<PricedLine> lines, int userUses) {
    return CouponRules.firstViolation(CouponRules.STANDARD, new CouponContext(c, NOW, subtotal, lines, userUses));
  }

  @Test
  void validCouponHasNoViolation() {
    assertEquals(Optional.empty(), check(flat(5000, 99900), 100000, List.of(line(DAIRY, 100000)), 0));
  }

  @Test
  void inactiveOrOutsideDatesIsExpired() {
    assertEquals(Optional.of("EXPIRED"), check(coupon(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, null, null, false), 100, List.of(), 0));
    assertEquals(Optional.of("EXPIRED"), check(coupon(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, NOW.plusSeconds(1), null, true), 100, List.of(), 0));
    assertEquals(Optional.of("EXPIRED"), check(coupon(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, null, NOW.minusSeconds(1), true), 100, List.of(), 0));
  }

  @Test
  void belowMinimumOrder() {
    assertEquals(Optional.of("MIN_ORDER"), check(flat(5000, 99900), 99899, List.of(), 0));
    assertEquals(Optional.empty(), check(flat(5000, 99900), 99900, List.of(), 0));
  }

  @Test
  void categoryCouponNeedsAnItemOfThatCategory() {
    Coupon c = coupon(Coupon.PERCENT, 10, 0, 0, DAIRY, 0, 0, 0, null, null, true);
    assertEquals(Optional.of("CATEGORY"), check(c, 500, List.of(line(SNACKS, 500)), 0));
    assertEquals(Optional.empty(), check(c, 500, List.of(line(SNACKS, 300), line(DAIRY, 200)), 0));
  }

  @Test
  void perUserAndTotalLimits() {
    assertEquals(Optional.of("LIMIT_REACHED"), check(coupon(Coupon.FLAT, 1, 0, 0, null, 1, 0, 0, null, null, true), 100, List.of(), 1));
    assertEquals(Optional.of("LIMIT_REACHED"), check(coupon(Coupon.FLAT, 1, 0, 0, null, 0, 10, 10, null, null, true), 100, List.of(), 0));
    assertEquals(Optional.empty(), check(coupon(Coupon.FLAT, 1, 0, 0, null, 2, 10, 9, null, null, true), 100, List.of(), 1));
  }

  @Test
  void flatDiscountIsCappedByTheBase() {
    assertEquals(5000, CouponMath.discount(flat(5000, 0), 100000));
    assertEquals(300, CouponMath.discount(flat(5000, 0), 300));
  }

  @Test
  void percentDiscountRoundsDownAndHonoursMax() {
    Coupon c = coupon(Coupon.PERCENT, 15, 0, 1000, null, 0, 0, 0, null, null, true);
    assertEquals(1000, CouponMath.discount(c, 100000)); // 15% = 15000 but capped
    assertEquals(149, CouponMath.discount(coupon(Coupon.PERCENT, 15, 0, 0, null, 0, 0, 0, null, null, true), 999)); // 149.85 -> 149
  }

  @Test
  void categoryCouponOnlyDiscountsItsOwnLines() {
    Coupon c = coupon(Coupon.PERCENT, 10, 0, 0, DAIRY, 0, 0, 0, null, null, true);
    List<PricedLine> lines = List.of(line(DAIRY, 20000), line(SNACKS, 80000));
    assertEquals(20000, CouponMath.base(c, lines, 100000));
    assertEquals(2000, CouponMath.discount(c, CouponMath.base(c, lines, 100000)));
  }
}
