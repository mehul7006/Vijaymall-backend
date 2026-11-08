package com.vijay.mall.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.cache.AppCaches.Name;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AppCachesTest {
  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-08T10:00:00Z"));
  private final Clock clock = new Clock() {
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
  };
  private final AppCaches caches = new AppCaches(clock);
  private final AtomicInteger loads = new AtomicInteger();

  private String load(String v) {
    loads.incrementAndGet();
    return v;
  }

  @Test
  void aSecondReadIsServedFromMemoryUntilTheTimeIsUp() {
    assertThat(caches.get(Name.SHOP, "1", () -> load("a"))).isEqualTo("a");
    assertThat(caches.get(Name.SHOP, "1", () -> load("b"))).as("still cached").isEqualTo("a");
    assertThat(loads).hasValue(1);
    now.set(now.get().plusSeconds(Name.SHOP.ttlSeconds - 1));
    assertThat(caches.get(Name.SHOP, "1", () -> load("c"))).isEqualTo("a");
    now.set(now.get().plusSeconds(2));
    assertThat(caches.get(Name.SHOP, "1", () -> load("d"))).as("expired, loaded again").isEqualTo("d");
    var stat = caches.stats().stream().filter(s -> s.name().equals("shop")).findFirst().orElseThrow();
    assertThat(stat.hits()).isEqualTo(2);
    assertThat(stat.misses()).isEqualTo(2);
    assertThat(stat.entries()).isEqualTo(1);
  }

  @Test
  void differentKeysAndDifferentCachesDoNotMix() {
    assertThat(caches.get(Name.DASHBOARD, "monday", () -> "m")).isEqualTo("m");
    assertThat(caches.get(Name.DASHBOARD, "tuesday", () -> "t")).isEqualTo("t");
    assertThat(caches.get(Name.REPORT_SUMMARY, "monday", () -> "r")).isEqualTo("r");
  }

  @Test
  void emptyingACacheMakesTheNextReadFresh() {
    caches.get(Name.CATEGORIES, "active", () -> load("old"));
    caches.invalidate(Name.CATEGORIES);
    assertThat(caches.get(Name.CATEGORIES, "active", () -> load("new"))).isEqualTo("new");
    var stat = caches.stats().stream().filter(s -> s.name().equals("categories")).findFirst().orElseThrow();
    assertThat(stat.clears()).isEqualTo(1);
    assertThat(stat.lastClearedAt()).isNotNull();
    caches.invalidateAll();
    assertThat(caches.stats()).allSatisfy(s -> assertThat(s.entries()).isZero());
    assertThat(caches.stats().stream().filter(s -> s.name().equals("units")).findFirst().orElseThrow().lastClearedAt()).isNotNull();
  }

  @Test
  void aFailedLoadCachesNothingAndNullsAreNotKept() {
    assertThatThrownBy(() -> caches.get(Name.UNITS, "active", () -> { throw new IllegalStateException("db down"); })).isInstanceOf(IllegalStateException.class);
    Object none = caches.get(Name.UNITS, "active", () -> (Object) null);
    assertThat(none).isNull();
    assertThat(caches.get(Name.UNITS, "active", () -> load("ok"))).isEqualTo("ok");
    assertThat(loads).hasValue(1);
  }

  @Test
  void aRunawayNumberOfKeysCannotGrowMemory() {
    for (int i = 0; i < Name.SHOP.maxEntries + 3; i++) caches.get(Name.SHOP, "k" + i, () -> "v");
    assertThat(caches.stats().stream().filter(s -> s.name().equals("shop")).findFirst().orElseThrow().entries()).isLessThanOrEqualTo(Name.SHOP.maxEntries);
  }

  @Test
  void theAdminCanSwitchCachingOffOrChangeHowLongThingsAreKept() {
    RuntimeSettings settings = mock(RuntimeSettings.class);
    when(settings.getBool(Settings.CACHE_ENABLED)).thenReturn(true);
    when(settings.getInt(any())).thenReturn(10);
    caches.setSettings(settings);
    caches.get(Name.SHOP, "1", () -> load("a"));
    now.set(now.get().plusSeconds(11));
    assertThat(caches.get(Name.SHOP, "1", () -> load("b"))).as("10 s instead of 300 s").isEqualTo("b");
    assertThat(caches.stats().get(0).ttlSeconds()).isEqualTo(10);

    when(settings.getInt(any())).thenReturn(0);
    assertThat(caches.get(Name.SHOP, "1", () -> load("c"))).as("0 seconds = do not keep").isEqualTo("c");
    assertThat(caches.get(Name.SHOP, "1", () -> load("d"))).isEqualTo("d");

    when(settings.getInt(any())).thenReturn(300);
    when(settings.getBool(Settings.CACHE_ENABLED)).thenReturn(false);
    assertThat(caches.get(Name.SHOP, "1", () -> load("e"))).isEqualTo("e");
    assertThat(caches.get(Name.SHOP, "1", () -> load("f"))).as("switched off: always fresh").isEqualTo("f");
    var stat = caches.stats().get(0);
    assertThat(stat.bypassed()).isEqualTo(4);
    assertThat(stat.ttlSeconds()).as("shown as 0 while off").isZero();
  }

  @Test
  void everyCacheHasAMetricAndALoadTimer() {
    var registry = new SimpleMeterRegistry();
    caches.bindTo(registry);
    caches.get(Name.SHOP, "1", () -> "a");
    caches.get(Name.SHOP, "1", () -> "a");
    assertThat(registry.get("mall.cache.hits").tag("cache", "shop").functionCounter().count()).isEqualTo(1);
    assertThat(registry.get("mall.cache.misses").tag("cache", "shop").functionCounter().count()).isEqualTo(1);
    assertThat(registry.get("mall.cache.entries").tag("cache", "shop").gauge().value()).isEqualTo(1);
    assertThat(registry.get("mall.cache.load").tag("cache", "shop").timer().count()).isEqualTo(1);
    caches.invalidate(Name.SHOP);
    assertThat(registry.get("mall.cache.clears").tag("cache", "shop").functionCounter().count()).isEqualTo(1);
    assertThat(registry.find("mall.cache.bypassed").meters()).hasSize(Name.values().length);
  }

  @Test
  void aSlowLoadIsStillCachedAndReturned() {
    assertThat(caches.get(Name.DASHBOARD, "x", () -> {
      now.set(now.get().plusSeconds(1));
      try { Thread.sleep(AppCaches.SLOW_LOAD_MS + 20); } catch (InterruptedException e) { throw new IllegalStateException(e); }
      return "slow";
    })).isEqualTo("slow");
  }

  @Test
  void everyCacheCanBeFoundByItsName() {
    for (Name n : Name.values()) assertThat(Name.byKey(n.key)).isEqualTo(n);
    assertThat(Name.byKey("nope")).isNull();
    assertThat(Settings.ttlOf(Name.DASHBOARD).key()).isEqualTo("cache.ttl.dashboard");
    assertThat(Settings.find("cache.ttl.shop-profile")).isNotNull();
  }
}
