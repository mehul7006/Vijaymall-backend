package com.vijay.mall.promotion.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.promotion.domain.AppliesTo;
import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.promotion.infra.CouponRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Item coupons in the promotion service: validation, messages that name the items, the flash switch and the admin view. */
class PromotionServiceItemsTest {
  private final CouponRepository repo = mock(CouponRepository.class);
  private final PromotionService service = new PromotionService(repo, mock(AuditService.class),
      Clock.fixed(Instant.parse("2027-03-01T10:00:00Z"), ZoneOffset.UTC));
  private final UUID dal = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();

  private Coupon coupon(List<UUID> cats, List<UUID> products, List<UUID> groups) {
    return new Coupon(UUID.randomUUID(), "ITEMS", Coupon.PERCENT, 10, 500, 0, null, 0, 0, 0, null, null, true, true, false, cats, products, groups);
  }

  private static CouponRepository.Names names(Map<UUID, AppliesTo.Named> c, Map<UUID, AppliesTo.Named> p, Map<UUID, AppliesTo.Named> g) {
    return new CouponRepository.Names(c, p, g);
  }

  private PricedLine line(UUID product, UUID flash, long total) {
    return new PricedLine(product, UUID.randomUUID(), "x", 1, total, total, true, true, "x", "", flash, 0, product);
  }

  private static String reason(ApiException e) { return (String) e.details().get("reason"); }

  @Test
  void aCouponThatCoversNothingInTheCartNamesWhatItIsFor() {
    Coupon c = coupon(List.of(), List.of(dal, rice), List.of());
    when(repo.findByCode("ITEMS")).thenReturn(Optional.of(c));
    when(repo.namesFor(any())).thenReturn(names(Map.of(), Map.of(dal, new AppliesTo.Named(dal, "Dal", true),
        rice, new AppliesTo.Named(rice, "Basmati Rice 5 kg", true)), Map.of()));
    assertThatThrownBy(() -> service.apply("items", null, List.of(line(other, null, 1000)), 1000, () -> 0))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(reason(e)).isEqualTo("ITEM");
          assertThat(e.getMessage()).isEqualTo("This coupon is for Dal and Basmati Rice 5 kg; none are in your cart.");
        });
  }

  @Test
  void messagesFallBackWhenNoNamesAreKnown() {
    assertThat(PromotionService.invalid("ITEM", null).getMessage()).contains("certain items");
    assertThat(PromotionService.invalid("CATEGORY", null).getMessage()).contains("certain categories");
    assertThat(PromotionService.invalid("FLASH_ITEMS", null).getMessage()).contains("flash sale");
    var named = new AppliesTo("CATEGORIES", List.of(new AppliesTo.Named(dal, "Dairy", true)), List.of(), List.of());
    assertThat(PromotionService.invalid("CATEGORY", null, named).getMessage()).isEqualTo("This coupon is for Dairy; none are in your cart.");
  }

  @Test
  void theCouponAppliesOnFlashLinesUnlessTheAdminTurnsItOff() {
    Coupon c = coupon(List.of(), List.of(dal), List.of());
    when(repo.findByCode("ITEMS")).thenReturn(Optional.of(c));
    var lines = List.of(line(dal, UUID.randomUUID(), 1000), line(other, null, 1000));
    assertThat(service.apply("items", null, lines, 2000, () -> 0).discountPaise()).isEqualTo(100);
    assertThat(service.apply("items", null, lines, 2000, () -> 0).minOrderPaise()).isEqualTo(500);
    RuntimeSettings settings = mock(RuntimeSettings.class);
    when(settings.getBool(Settings.COUPON_ALLOW_ON_FLASH)).thenReturn(false);
    service.setSettings(settings);
    assertThatThrownBy(() -> service.apply("items", null, lines, 2000, () -> 0))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(reason(e)).isEqualTo("FLASH_ITEMS"));
    when(settings.getBool(Settings.COUPON_ALLOW_ON_FLASH)).thenReturn(true);
    assertThat(service.apply("items", null, lines, 2000, () -> 0).discountPaise()).isEqualTo(100);
  }

  @Test
  void adminListsCouponsWithNamesAndWarnsAboutSwitchedOffItems() {
    Coupon c = coupon(List.of(), List.of(dal), List.of(rice));
    when(repo.all()).thenReturn(List.of(c));
    when(repo.find(c.id())).thenReturn(Optional.of(c));
    when(repo.namesFor(any())).thenReturn(names(Map.of(), Map.of(dal, new AppliesTo.Named(dal, "Dal", false)),
        Map.of(rice, new AppliesTo.Named(rice, "Rice", false))));
    var view = service.listWithNames().get(0);
    assertThat(view.appliesTo().type()).isEqualTo("ITEMS");
    assertThat(view.warnings()).hasSize(2);
    assertThat(service.detail(c.id()).warnings().get(0)).contains("Dal is switched off");
    assertThatThrownBy(() -> service.detail(UUID.randomUUID())).isInstanceOf(ApiException.class);
    when(repo.all()).thenReturn(List.of());
    assertThat(service.listWithNames()).isEmpty();
  }

  @Test
  void idsAreCheckedWithTheFieldAndPosition() {
    Coupon bad = coupon(List.of(), List.of(dal, other), List.of());
    when(repo.existingCategories(any())).thenReturn(Set.of());
    when(repo.existingProducts(any())).thenReturn(Set.of(dal));
    when(repo.existingGroups(any())).thenReturn(Set.of());
    assertThatThrownBy(() -> service.create(bad, UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details().toString()).contains("productIds[1]"));
    Coupon badGroup = coupon(List.of(), List.of(), List.of(other));
    assertThatThrownBy(() -> service.update(UUID.randomUUID(), badGroup, UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details().toString()).contains("productGroupIds[0]"));
    Coupon badCategory = coupon(List.of(other), List.of(), List.of());
    assertThatThrownBy(() -> service.create(badCategory, null)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details().toString()).contains("categoryIds[0]"));
    List<UUID> many = new ArrayList<>();
    for (int i = 0; i < 201; i++) many.add(UUID.randomUUID());
    assertThatThrownBy(() -> service.create(coupon(List.of(), many, List.of()), null)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details().toString()).contains("productIds"));
  }
}
