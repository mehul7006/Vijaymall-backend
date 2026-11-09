package com.vijay.mall.flash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.flash.domain.FlashKind;
import com.vijay.mall.flash.domain.FlashMath;
import com.vijay.mall.flash.domain.FlashMath.Rule;
import com.vijay.mall.flash.domain.FlashReportMath;
import com.vijay.mall.flash.domain.FlashStatus;
import com.vijay.mall.flash.domain.FlashWindow;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FlashDomainTest {
  private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");

  // ---------- price math ----------
  @Test
  void percentIsRoundedHalfUpToThePaisa() {
    // 999 * 85% = 849.15 -> 849 ; 1005 * 85% = 854.25 -> 854 ; 10 paise-cases: 1010 * 95% = 959.5 -> 960 (half up)
    assertThat(FlashMath.salePrice(new Rule(FlashKind.PERCENT, 15), 999)).contains(849L);
    assertThat(FlashMath.salePrice(new Rule(FlashKind.PERCENT, 15), 1005)).contains(854L);
    assertThat(FlashMath.salePrice(new Rule(FlashKind.PERCENT, 5), 1010)).contains(960L);
    assertThat(FlashMath.salePrice(new Rule(FlashKind.PERCENT, 10), 12000)).contains(10800L);
  }

  @Test
  void priceNeverGoesBelowOneRupeeOrAboveTheListPrice() {
    assertThat(FlashMath.salePrice(new Rule(FlashKind.PERCENT, 90), 150)).contains(100L); // 15 -> floored to Rs 1
    assertThat(FlashMath.salePrice(new Rule(FlashKind.FIXED_PRICE, 50), 5000)).contains(100L);
    assertThat(FlashMath.salePrice(new Rule(FlashKind.FIXED_PRICE, 4000), 5000)).contains(4000L);
    // the list price fell to the fixed price or below: no discount left
    assertThat(FlashMath.salePrice(new Rule(FlashKind.FIXED_PRICE, 4000), 4000)).isEmpty();
    assertThat(FlashMath.salePrice(new Rule(FlashKind.FIXED_PRICE, 4000), 3500)).isEmpty();
    // a product at Rs 1 or less cannot be discounted
    assertThat(FlashMath.salePrice(new Rule(FlashKind.PERCENT, 50), 100)).isEmpty();
    assertThat(FlashMath.salePrice(new Rule(FlashKind.PERCENT, 50), 0)).isEmpty();
    // a percent that rounds to no change (1% of Rs 1.01)
    assertThat(FlashMath.salePrice(new Rule(FlashKind.PERCENT, 1), 101)).contains(100L);
    assertThat(FlashMath.salePrice(null, 5000)).isEmpty();
    assertThat(FlashMath.salePrice(new Rule(FlashKind.NONE, 0), 5000)).isEmpty();
  }

  @Test
  void itemOverrideBeatsTheSaleDefault() {
    Rule sale = new Rule(FlashKind.PERCENT, 10);
    assertThat(FlashMath.effective(FlashKind.FIXED_PRICE, 2500, FlashKind.PERCENT, 10)).isEqualTo(new Rule(FlashKind.FIXED_PRICE, 2500));
    assertThat(FlashMath.effective(null, 0, FlashKind.PERCENT, 10)).isEqualTo(sale);
    assertThat(FlashMath.effective(FlashKind.NONE, 0, FlashKind.PERCENT, 10)).isEqualTo(sale);
    assertThat(FlashMath.effective(FlashKind.NONE, 0, FlashKind.NONE, 0).usable()).isFalse();
    assertThat(sale.usable()).isTrue();
  }

  @Test
  void ruleProblemsAreReported() {
    assertThat(FlashMath.problemWith(FlashKind.PERCENT, 0)).isNotNull();
    assertThat(FlashMath.problemWith(FlashKind.PERCENT, 91)).isNotNull();
    assertThat(FlashMath.problemWith(FlashKind.PERCENT, 1)).isNull();
    assertThat(FlashMath.problemWith(FlashKind.PERCENT, 90)).isNull();
    assertThat(FlashMath.problemWith(FlashKind.FIXED_PRICE, 99)).isNotNull();
    assertThat(FlashMath.problemWith(FlashKind.FIXED_PRICE, 100)).isNull();
    assertThat(FlashMath.problemWith(FlashKind.NONE, 0)).isNull();
    assertThat(FlashMath.fixedPriceBelow(new Rule(FlashKind.FIXED_PRICE, 500), 500)).isFalse();
    assertThat(FlashMath.fixedPriceBelow(new Rule(FlashKind.FIXED_PRICE, 499), 500)).isTrue();
    assertThat(FlashMath.fixedPriceBelow(new Rule(FlashKind.PERCENT, 50), 100)).isTrue();
  }

  @Test
  void discountPercentIsRounded() {
    assertThat(FlashMath.discountPercent(1000, 850)).isEqualTo(15);
    assertThat(FlashMath.discountPercent(999, 849)).isEqualTo(15);
    assertThat(FlashMath.discountPercent(300, 200)).isEqualTo(33);
    assertThat(FlashMath.discountPercent(0, 0)).isZero();
  }

  @Test
  void saleQuantityIsCutToTheCapAndToTheCustomerAllowance() {
    assertThat(FlashMath.saleQty(5, null, null)).isEqualTo(5);
    assertThat(FlashMath.saleQty(5, 3, null)).isEqualTo(3);
    assertThat(FlashMath.saleQty(5, 10, 2)).isEqualTo(2);
    assertThat(FlashMath.saleQty(5, 0, 2)).isZero();
    assertThat(FlashMath.saleQty(5, null, 0)).isZero();
    assertThat(FlashMath.saleQty(5, -1, null)).isZero();
  }

  @Test
  void kindsParse() {
    assertThat(FlashKind.parse(" percent ")).isEqualTo(FlashKind.PERCENT);
    assertThat(FlashKind.parse("fixed_price")).isEqualTo(FlashKind.FIXED_PRICE);
    assertThat(FlashKind.parse(null)).isEqualTo(FlashKind.NONE);
    assertThat(FlashKind.parse(" ")).isEqualTo(FlashKind.NONE);
    assertThatThrownBy(() -> FlashKind.parse("HALF")).isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- status ----------
  @Test
  void statusComesFromTheClockAndCancellation() {
    Instant start = T0.plusSeconds(60);
    Instant end = T0.plusSeconds(120);
    assertThat(FlashStatus.of(start, end, null, T0)).isEqualTo(FlashStatus.SCHEDULED);
    assertThat(FlashStatus.of(start, end, null, start)).isEqualTo(FlashStatus.ACTIVE);
    assertThat(FlashStatus.of(start, end, null, end.minusMillis(1))).isEqualTo(FlashStatus.ACTIVE);
    assertThat(FlashStatus.of(start, end, null, end)).isEqualTo(FlashStatus.ENDED);
    assertThat(FlashStatus.of(start, end, T0, T0)).isEqualTo(FlashStatus.CANCELLED);
    assertThat(FlashStatus.of(start, end, T0, end.plusSeconds(5))).isEqualTo(FlashStatus.CANCELLED);
  }

  // ---------- windows ----------
  @Test
  void windowsOverlapOnlyWhenTheyShareTime() {
    Instant a1 = T0, a2 = T0.plusSeconds(100);
    assertThat(FlashWindow.overlaps(a1, a2, T0.plusSeconds(50), T0.plusSeconds(150))).isTrue();
    assertThat(FlashWindow.overlaps(a1, a2, T0.minusSeconds(50), T0.plusSeconds(1))).isTrue();
    assertThat(FlashWindow.overlaps(a1, a2, T0.plusSeconds(10), T0.plusSeconds(20))).isTrue();
    assertThat(FlashWindow.overlaps(a1, a2, a2, a2.plusSeconds(10))).isFalse(); // touching
    assertThat(FlashWindow.overlaps(a1, a2, a1.minusSeconds(10), a1)).isFalse();
    assertThat(FlashWindow.overlaps(a1, a2, a2.plusSeconds(1), a2.plusSeconds(9))).isFalse();
  }

  @Test
  void windowRules() {
    Instant now = T0;
    assertThat(FlashWindow.problem(now, null, now, false)).contains("required");
    assertThat(FlashWindow.problem(now, now, now, false)).contains("after startsAt");
    assertThat(FlashWindow.problem(now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)), now, true)).contains("future");
    assertThat(FlashWindow.problem(now.minus(Duration.ofHours(1)), now.plusSeconds(60), now, false)).contains("past");
    assertThat(FlashWindow.problem(now.minus(Duration.ofHours(1)), now.plusSeconds(60), now, true)).isNull();
    assertThat(FlashWindow.problem(now.minusSeconds(30), now.plusSeconds(60), now, false)).isNull(); // inside the grace
    assertThat(FlashWindow.problem(now, now.plus(Duration.ofDays(30)), now, false)).isNull();
    assertThat(FlashWindow.problem(now, now.plus(Duration.ofDays(30)).plusSeconds(1), now, false)).contains("30 days");
  }

  // ---------- report math ----------
  @Test
  void reportPercentages() {
    assertThat(FlashReportMath.averageDiscountPercent(8500, 1500)).isEqualTo(15.0);
    assertThat(FlashReportMath.averageDiscountPercent(2, 1)).isEqualTo(33.33);
    assertThat(FlashReportMath.averageDiscountPercent(0, 0)).isZero();
    assertThat(FlashReportMath.sellThroughPercent(25, 100)).isEqualTo(25.0);
    assertThat(FlashReportMath.sellThroughPercent(1, 3)).isEqualTo(33.33);
    assertThat(FlashReportMath.sellThroughPercent(150, 100)).isEqualTo(100.0);
    assertThat(FlashReportMath.sellThroughPercent(5, 0)).isNull();
  }
}
