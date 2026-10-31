package com.vijay.mall.promotion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.catalog.domain.PricedLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Boundary cases that CouponRulesTest does not cover: inclusive windows, rule order, laziness, rounding. */
class CouponBoundaryTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private static Coupon c(String kind, long value, long min, long max, UUID cat, int perUser, int total, int used,
                          Instant start, Instant end, boolean active) {
    return new Coupon(UUID.randomUUID(), "X", kind, value, min, max, cat, perUser, total, used, start, end, active);
  }

  private static Optional<String> check(Coupon c, long subtotal, List<PricedLine> lines, int userUses) {
    return CouponRules.firstViolation(CouponRules.STANDARD, new CouponContext(c, NOW, subtotal, lines, userUses));
  }

  @ParameterizedTest
  @CsvSource({"0,0,false", "-1,0,false", "1,0,true", "0,-1,false", "0,1,true", "-3600,3600,false", "1,-1,true"})
  void validityWindowIsInclusiveAtBothEnds(long startOffset, long endOffset, boolean expired) {
    // offsets of 0 mean "exactly now"; 1/-1 are outside. start offset is relative to NOW (start = NOW + offset)
    Instant start = startOffset == 0 && endOffset == 0 ? NOW : NOW.plusSeconds(startOffset);
    Instant end = NOW.plusSeconds(endOffset);
    boolean outside = NOW.isBefore(start) || NOW.isAfter(end);
    assertThat(outside).isEqualTo(expired || (startOffset == 1 || endOffset == -1));
    Optional<String> result = check(c(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, start, end, true), 100, List.of(), 0);
    assertThat(result.isPresent()).isEqualTo(outside);
    result.ifPresent(r -> assertThat(r).isEqualTo("EXPIRED"));
  }

  @Test
  void startingExactlyNowAndEndingExactlyNowAreBothStillValid() {
    assertThat(check(c(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, NOW, null, true), 100, List.of(), 0)).isEmpty();
    assertThat(check(c(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, null, NOW, true), 100, List.of(), 0)).isEmpty();
    assertThat(check(c(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, NOW, NOW, true), 100, List.of(), 0)).isEmpty();
  }

  @Test
  void rulesAreCheckedInOrderSoTheFirstProblemWins() {
    // inactive AND below minimum AND wrong category AND over limits: EXPIRED comes first
    Coupon all = c(Coupon.FLAT, 1, 1000, 0, UUID.randomUUID(), 1, 1, 1, null, null, false);
    assertThat(check(all, 10, List.of(), 5)).contains("EXPIRED");
    Coupon active = c(Coupon.FLAT, 1, 1000, 0, UUID.randomUUID(), 1, 1, 1, null, null, true);
    assertThat(check(active, 10, List.of(), 5)).contains("MIN_ORDER");
    assertThat(check(active, 1000, List.of(), 5)).contains("CATEGORY");
  }

  @Test
  void laterRulesDoNotRunOnceOneHasFailed() {
    List<String> ran = new ArrayList<>();
    List<CouponRule> rules = List.of(
        ctx -> { ran.add("first"); return Optional.empty(); },
        ctx -> { ran.add("second"); return Optional.of("BROKEN"); },
        ctx -> { ran.add("third"); return Optional.of("NEVER"); });
    Optional<String> v = CouponRules.firstViolation(rules, new CouponContext(c(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, null, null, true), NOW, 1, List.of(), 0));
    assertThat(v).contains("BROKEN");
    assertThat(ran).containsExactly("first", "second");
    assertThat(CouponRules.firstViolation(List.of(), new CouponContext(null, NOW, 0, List.of(), 0))).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"0,0,0,false", "1,0,0,true", "2,1,0,false", "2,2,0,true", "0,0,1,false", "0,0,4,false", "3,0,2,false", "3,0,3,true"})
  void perUserLimitBlocksAtTheLimitExactlyAndZeroMeansUnlimited(int limit, int ignored, int uses, boolean blocked) {
    Optional<String> r = check(c(Coupon.FLAT, 1, 0, 0, null, limit, 0, 0, null, null, true), 100, List.of(), uses);
    assertThat(r.isPresent()).isEqualTo(limit > 0 && uses >= limit);
    assertThat(r.isPresent()).as("limit=%d uses=%d", limit, uses).isEqualTo(blocked || (limit > 0 && uses > limit));
  }

  @ParameterizedTest
  @CsvSource({"0,0,false", "0,500,false", "5,4,false", "5,5,true", "5,6,true", "1,0,false"})
  void totalLimitBlocksAtTheLimitExactlyAndZeroMeansUnlimited(int limit, int used, boolean blocked) {
    Optional<String> r = check(c(Coupon.FLAT, 1, 0, 0, null, 0, limit, used, null, null, true), 100, List.of(), 0);
    assertThat(r.isPresent()).isEqualTo(blocked);
    r.ifPresent(x -> assertThat(x).isEqualTo("LIMIT_REACHED"));
  }

  @Test
  void zeroMinimumNeverBlocksEvenAnEmptyCart() {
    assertThat(check(c(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, null, null, true), 0, List.of(), 0)).isEmpty();
  }

  // ---------- math ----------
  @ParameterizedTest
  @CsvSource({
      "PERCENT,10,0,0,0",           // nothing to discount
      "PERCENT,100,0,10000,10000",  // 100 percent is the whole base
      "PERCENT,50,0,333,166",       // rounds down
      "PERCENT,10,0,9,0",           // below one paise rounds to zero
      "PERCENT,10,500,100000,500",  // capped by max
      "PERCENT,10,20000,100000,10000", // max above the raw value changes nothing
      "FLAT,5000,0,5000,5000",      // exactly the base
      "FLAT,5000,0,4999,4999",      // never more than the base
      "FLAT,5000,300,100000,300",   // flat also honours max
      "FLAT,5000,0,0,0",
      "BOGO,5,0,10000,0"            // unknown kind gives nothing
  })
  void discountMath(String kind, long value, long max, long base, long expected) {
    assertThat(CouponMath.discount(c(kind, value, 0, max, null, 0, 0, 0, null, null, true), base)).isEqualTo(expected);
  }

  @Test
  void discountNeverGoesNegative() {
    assertThat(CouponMath.discount(c(Coupon.FLAT, 100, 0, 0, null, 0, 0, 0, null, null, true), -50)).isZero();
  }

  @Test
  void percentMathDoesNotOverflowOnLargeBases() {
    long base = 2_000_000_000_000L; // 20 billion rupees in paise
    assertThat(CouponMath.discount(c(Coupon.PERCENT, 15, 0, 0, null, 0, 0, 0, null, null, true), base)).isEqualTo(300_000_000_000L);
  }

  @Test
  void baseOfAnUncategorisedCouponIsTheSubtotalEvenWithoutLines() {
    assertThat(CouponMath.base(c(Coupon.FLAT, 1, 0, 0, null, 0, 0, 0, null, null, true), List.of(), 777)).isEqualTo(777);
  }

  @Test
  void baseOfACategoryCouponWithNoMatchingLineIsZero() {
    UUID dairy = UUID.randomUUID();
    PricedLine other = new PricedLine(UUID.randomUUID(), UUID.randomUUID(), "x", 1, 100, 100, true, true);
    assertThat(CouponMath.base(c(Coupon.FLAT, 1, 0, 0, dairy, 0, 0, 0, null, null, true), List.of(other), 100)).isZero();
  }
}
