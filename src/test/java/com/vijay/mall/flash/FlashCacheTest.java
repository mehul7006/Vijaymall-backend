package com.vijay.mall.flash;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.cache.AppCaches.Name;
import com.vijay.mall.common.cache.CacheInvalidator;
import com.vijay.mall.common.live.CatalogChanged;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FlashCacheTest {
  @Test
  void theFlashCacheIsShortLivedAndOnlyAFlashAnnouncementEmptiesIt() {
    AppCaches caches = new AppCaches(Clock.systemUTC());
    CacheInvalidator invalidator = new CacheInvalidator(caches);
    assertThat(Name.FLASH.ttlSeconds).isEqualTo(15);
    assertThat(Name.byKey("flash")).isEqualTo(Name.FLASH);
    caches.get(Name.FLASH, "defs", () -> "v");
    caches.get(Name.CATEGORIES, "k", () -> "v");
    invalidator.on(new CatalogChanged(CatalogChanged.CATALOG, Instant.now()));
    assertThat(caches.stats().stream().filter(s -> s.name().equals("flash")).findFirst().orElseThrow().entries()).isEqualTo(1);
    invalidator.on(new CatalogChanged(CatalogChanged.FLASH, Instant.now()));
    assertThat(caches.stats().stream().filter(s -> s.name().equals("flash")).findFirst().orElseThrow().entries()).isZero();
    assertThat(caches.stats().stream().filter(s -> s.name().equals("categories")).findFirst().orElseThrow().entries()).isEqualTo(0);
  }
}
