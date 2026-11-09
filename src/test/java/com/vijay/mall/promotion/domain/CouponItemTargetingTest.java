package com.vijay.mall.promotion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.catalog.domain.PricedLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which cart lines an item coupon covers, how it splits, and why it is refused. */
class CouponItemTargetingTest {
  private final UUID dairy = UUID.randomUUID();
  private final UUID snacks = UUID.randomUUID();
  private final UUID milkGroup = UUID.randomUUID();
  private final UUID milk = UUID.randomUUID();
  private final UUID chips = UUID.randomUUID();
  private final UUID dal = UUID.randomUUID();

  private PricedLine line(UUID product, UUID category, UUID group, long total, UUID flash) {
    return new PricedLine(product, category, "p", 1, total, total, true, true, "p", "", flash, flash == null ? 0 : total + 100, group);
  }

  private final List<PricedLine> cart = List.of(line(milk, dairy, milkGroup, 3333, null), line(chips, snacks, chips, 5000, null),
      line(dal, snacks, dal, 6667, null));

  private Coupon coupon(String kind, long value, long min, List<UUID> cats, List<UUID> products, List<UUID> groups) {
    return new Coupon(UUID.randomUUID(), "C", kind, value, min, 0, null, 0, 0, 0, null, null, true, true, false, cats, products, groups);
  }

  private Optional<String> reason(Coupon c, List<PricedLine> lines, boolean flash) {
    long subtotal = lines.stream().mapToLong(PricedLine::lineTotalPaise).sum();
    return CouponRules.firstViolation(CouponRules.STANDARD, new CouponContext(c, Instant.now(), subtotal, lines, 0, 0, flash));
  }

  @Test
  void aLineIsCoveredByItsCategoryItsProductOrItsGroup() {
    Coupon c = coupon(Coupon.FLAT, 100, 0, List.of(snacks), List.of(milk), List.of());
    assertThat(cart).allMatch(c::covers);
    Coupon byGroup = coupon(Coupon.FLAT, 100, 0, List.of(), List.of(), List.of(milkGroup));
    assertThat(cart.stream().map(byGroup::covers)).containsExactly(true, false, false);
    Coupon byProduct = coupon(Coupon.FLAT, 100, 0, List.of(), List.of(chips), List.of());
    assertThat(cart.stream().map(byProduct::covers)).containsExactly(false, true, false);
    Coupon none = coupon(Coupon.FLAT, 100, 0, List.of(), List.of(), List.of());
    assertThat(none.unrestricted()).isTrue();
    assertThat(cart).allMatch(none::covers);
    assertThat(byGroup.unrestricted()).isFalse();
    PricedLine noGroup = new PricedLine(UUID.randomUUID(), null, "x", 1, 10, 10, true, true);
    assertThat(byGroup.covers(noGroup)).isFalse();
  }

  @Test
  void mixedCouponSplitsOverTheCoveredLinesOnlyAndSumsExactly() {
    Coupon c = coupon(Coupon.PERCENT, 10, 0, List.of(dairy), List.of(dal), List.of());
    long base = CouponMath.base(c, cart, 15000);
    assertThat(base).isEqualTo(3333 + 6667);
    long discount = CouponMath.discount(c, base);
    long[] share = CouponMath.perLine(c, cart, discount);
    assertThat(share).containsExactly(333, 0, 667);
    assertThat(Arrays.stream(share).sum()).isEqualTo(discount);
  }

  @Test
  void anyAmountSplitsToTheLastPaise() {
    Coupon c = coupon(Coupon.FLAT, 1001, 0, List.of(), List.of(milk, dal), List.of());
    long[] share = CouponMath.perLine(c, cart, 1001);
    assertThat(share[1]).isZero();
    assertThat(share[0] + share[2]).isEqualTo(1001);
  }

  @Test
  void theMinimumCountsOnlyTheCoveredLines() {
    Coupon c = coupon(Coupon.FLAT, 100, 6000, List.of(), List.of(milk), List.of());
    assertThat(reason(c, cart, true)).contains("MIN_ORDER");
    Coupon ok = coupon(Coupon.FLAT, 100, 3333, List.of(), List.of(milk), List.of());
    assertThat(reason(ok, cart, true)).isEmpty();
  }

  @Test
  void noCoveredLineGivesItemOrCategoryReason() {
    assertThat(reason(coupon(Coupon.FLAT, 1, 0, List.of(), List.of(UUID.randomUUID()), List.of()), cart, true)).contains("ITEM");
    assertThat(reason(coupon(Coupon.FLAT, 1, 0, List.of(), List.of(), List.of(UUID.randomUUID())), cart, true)).contains("ITEM");
    assertThat(reason(coupon(Coupon.FLAT, 1, 0, List.of(UUID.randomUUID()), List.of(), List.of()), cart, true)).contains("CATEGORY");
    assertThat(reason(coupon(Coupon.FLAT, 1, 0, List.of(), List.of(), List.of()), List.of(), true)).isEmpty();
  }

  @Test
  void flashLinesAreLeftOutWhenTheSettingIsOff() {
    List<PricedLine> lines = new ArrayList<>(cart);
    lines.set(0, line(milk, dairy, milkGroup, 3000, UUID.randomUUID()));
    Coupon all = coupon(Coupon.FLAT, 500, 0, List.of(), List.of(), List.of());
    assertThat(CouponMath.base(all, lines, 14667, true)).isEqualTo(14667);
    assertThat(CouponMath.base(all, lines, 14667, false)).isEqualTo(11667);
    assertThat(CouponMath.perLine(all, lines, 500, false)[0]).isZero();
    assertThat(CouponMath.perLine(all, lines, 500, true)[0]).isPositive();
    Coupon milkOnly = coupon(Coupon.FLAT, 500, 0, List.of(), List.of(milk), List.of());
    assertThat(reason(milkOnly, lines, false)).contains("FLASH_ITEMS");
    assertThat(reason(milkOnly, lines, true)).isEmpty();
    List<PricedLine> allFlash = List.of(line(milk, dairy, milkGroup, 3000, UUID.randomUUID()));
    assertThat(reason(all, allFlash, false)).contains("FLASH_ITEMS");
    assertThat(reason(all, List.of(), false)).isEmpty();
  }

  @Test
  void appliesToDescribesTheCoupon() {
    var cat = new AppliesTo.Named(dairy, "Dairy", true);
    var prod = new AppliesTo.Named(milk, "Basmati Rice 5 kg", true);
    var grp = new AppliesTo.Named(milkGroup, "Dal", true);
    assertThat(AppliesTo.EVERYTHING.text()).isEmpty();
    assertThat(AppliesTo.of(List.of(), List.of(), List.of()).type()).isEqualTo("ALL");
    assertThat(AppliesTo.of(List.of(cat), List.of(), List.of()).type()).isEqualTo("CATEGORIES");
    assertThat(AppliesTo.of(List.of(), List.of(prod), List.of(grp)).type()).isEqualTo("ITEMS");
    AppliesTo mixed = AppliesTo.of(List.of(cat), List.of(prod), List.of(grp));
    assertThat(mixed.type()).isEqualTo("MIXED");
    assertThat(mixed.names()).containsExactly("Dairy", "Basmati Rice 5 kg", "Dal");
    assertThat(mixed.text()).isEqualTo("Dairy, Basmati Rice 5 kg and Dal");
    assertThat(AppliesTo.of(List.of(cat), List.of(), List.of()).text()).isEqualTo("Dairy");
  }

  @Test
  void publicViewCarriesTheItemLists() {
    Coupon c = coupon(Coupon.FLAT, 100, 0, List.of(dairy), List.of(milk), List.of(milkGroup));
    PublicCoupon bare = PublicCoupon.from(c);
    assertThat(bare.appliesTo()).isEqualTo("MIXED");
    assertThat(bare.productIds()).containsExactly(milk);
    assertThat(bare.productGroupIds()).containsExactly(milkGroup);
    PublicCoupon named = PublicCoupon.from(c, new AppliesTo("MIXED", List.of(), List.of(), List.of()));
    assertThat(named.appliesToNames()).isEmpty();
    assertThat(PublicCoupon.from(coupon(Coupon.FLAT, 1, 0, List.of(), List.of(), List.of())).appliesTo()).isEqualTo("ALL");
    assertThat(new PublicCoupon("A", "FLAT", 1, 0, 0, null, null, null, true, false, 0, List.of(dairy)).appliesTo()).isEqualTo("CATEGORIES");
    assertThat(new PublicCoupon("A", "FLAT", 1, 0, 0, null, null, null, true, false, 0, List.of()).appliesTo()).isEqualTo("ALL");
  }

  @Test
  void withItemsKeepsEverythingElse() {
    Coupon c = coupon(Coupon.FLAT, 100, 5, List.of(dairy), List.of(), List.of()).withItems(List.of(milk), List.of(milkGroup));
    assertThat(c.productIds()).containsExactly(milk);
    assertThat(c.productGroupIds()).containsExactly(milkGroup);
    assertThat(c.categoryIds()).containsExactly(dairy);
    assertThat(c.withCategories(List.of(snacks)).productIds()).containsExactly(milk);
  }
}
