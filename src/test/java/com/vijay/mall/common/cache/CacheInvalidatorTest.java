package com.vijay.mall.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.cache.AppCaches.Name;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.config.SettingsChanged;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CacheInvalidatorTest {
  private final AppCaches caches = new AppCaches(Clock.systemUTC());
  private final CacheInvalidator invalidator = new CacheInvalidator(caches);

  private void fillAll() { for (Name n : Name.values()) caches.get(n, "k", () -> "v"); }

  private boolean empty(Name n) { return caches.stats().stream().filter(s -> s.name().equals(n.key)).findFirst().orElseThrow().entries() == 0; }

  @Test
  void eachAnnouncedAreaEmptiesOnlyTheCachesBehindIt() {
    fillAll();
    invalidator.on(new CatalogChanged(CatalogChanged.CATALOG, Instant.now()));
    assertThat(empty(Name.CATEGORIES)).isTrue();
    assertThat(empty(Name.BANNERS)).isTrue();
    assertThat(empty(Name.PRODUCT_LIST)).as("an admin product edit shows in the shop at once").isTrue();
    assertThat(empty(Name.ORDER_COUNTS)).as("order totals only expire by time").isFalse();
    assertThat(empty(Name.SHOP)).isFalse();

    invalidator.on(new CatalogChanged(CatalogChanged.COUPONS, Instant.now()));
    assertThat(empty(Name.COUPONS)).isTrue();
    invalidator.on(new CatalogChanged(CatalogChanged.UNITS, Instant.now()));
    assertThat(empty(Name.UNITS)).isTrue();
    invalidator.on(new CatalogChanged(CatalogChanged.PAYMENT_METHODS, Instant.now()));
    assertThat(empty(Name.PAYMENT_METHODS)).isTrue();
    assertThat(empty(Name.SHOP)).isFalse();
    invalidator.on(new CatalogChanged(CatalogChanged.SHOP, Instant.now()));
    assertThat(empty(Name.SHOP)).isTrue();
    assertThat(empty(Name.SHOP_PROFILE)).isTrue();
    assertThat(empty(Name.DASHBOARD)).as("untouched").isFalse();
  }

  @Test
  void anAreaNothingIsCachedForChangesNothing() {
    fillAll();
    invalidator.on(new CatalogChanged("SOMETHING_ELSE", Instant.now()));
    assertThat(caches.stats()).allSatisfy(s -> assertThat(s.entries()).isEqualTo(1));
  }

  @Test
  void changingACacheSettingStartsEverythingFromEmptyButOtherSettingsDoNot() {
    fillAll();
    invalidator.on(new SettingsChanged(Set.of("delivery.feePaise")));
    assertThat(empty(Name.SHOP)).isFalse();
    invalidator.on(new SettingsChanged(Set.of("cache.ttl.shop")));
    assertThat(caches.stats()).allSatisfy(s -> assertThat(s.entries()).isZero());
  }
}
