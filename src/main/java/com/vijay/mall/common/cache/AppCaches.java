package com.vijay.mall.common.cache;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The server's in-memory caches for data that is read far more often than it changes (shop details, categories, banners, units,
 * coin rules, payment methods, public coupons, dashboard and report figures). Each cache has a time limit, so even without any help
 * it is never stale for longer than that, AND is emptied the moment the admin changes the data it holds. The admin can also empty
 * any cache or all of them from the app ("hard refresh"). Products, prices-with-stock, carts and orders are deliberately NOT cached:
 * they must always be exact. Caches are per server; with several servers the time limit bounds the difference.
 */
@Component
public class AppCaches implements MeterBinder {
  /** The caches. ttlSeconds is the longest a value is kept; maxEntries bounds memory for caches with many keys. */
  public enum Name {
    SHOP("shop", 300, 4, "Shop name, address, phone, billing details (used on every bill and order number)"),
    SHOP_PROFILE("shop-profile", 300, 4, "Logo, opening hours, contact links shown in the app"),
    CATEGORIES("categories", 300, 4, "Product categories shown in the shop"),
    BANNERS("banners", 300, 4, "Hot-sale banners"),
    UNITS("units", 600, 4, "Units of measure (kg, pack, ...)"),
    COIN_SETTINGS("coin-settings", 60, 4, "How much a coin is worth and its limits (read on every quote)"),
    COIN_RULES("coin-rules", 60, 4, "How coins are earned (read on every quote)"),
    PAYMENT_METHODS("payment-methods", 60, 4, "Which payment methods are switched on"),
    COUPONS("coupons", 60, 4, "Active coupons offered in the app"),
    DASHBOARD("dashboard", 30, 8, "Admin dashboard figures (heavy to compute)"),
    REPORT_SUMMARY("report-summary", 60, 60, "Sales report for a date range (heavy to compute)"),
    FLASH("flash", 15, 4, "Flash sale definitions (times, discounts, caps); units left are never cached");

    /** Short name used in URLs, metrics and settings, for example {@code categories}. */
    public final String key;
    /** Default life of a value, in seconds. */
    public final int ttlSeconds;
    /** How many different keys the cache may hold before it is emptied. */
    public final int maxEntries;
    /** What the cache holds, in plain words, for the admin screen. */
    public final String description;

    Name(String key, int ttlSeconds, int maxEntries, String description) {
      this.key = key;
      this.ttlSeconds = ttlSeconds;
      this.maxEntries = maxEntries;
      this.description = description;
    }

    /** The cache with this key, or {@code null} if there is none. */
    public static Name byKey(String key) {
      for (Name n : values()) if (n.key.equals(key)) return n;
      return null;
    }
  }

  /** What the admin screen shows for one cache. */
  public record Stat(String name, String description, int ttlSeconds, int entries, long hits, long misses, long bypassed, long clears,
                     Long lastClearedAt) {}

  private record Entry(Object value, long expiresAt) {}

  private static final class Store {
    final ConcurrentHashMap<Object, Entry> map = new ConcurrentHashMap<>();
    final AtomicLong hits = new AtomicLong();
    final AtomicLong misses = new AtomicLong();
    final AtomicLong clears = new AtomicLong();
    final AtomicLong bypassed = new AtomicLong();
    volatile long lastCleared;
  }

  private static final Logger log = LoggerFactory.getLogger(AppCaches.class);
  static final long SLOW_LOAD_MS = 1000;

  private final Clock clock;
  private final Map<Name, Store> stores = new EnumMap<>(Name.class);
  private RuntimeSettings settings;
  private volatile MeterRegistry registry;

  /** Optional (plain unit tests run without it): the admin can switch caching off or change how long each cache keeps its data. */
  @Autowired(required = false)
  void setSettings(RuntimeSettings settings) { this.settings = settings; }

  private boolean enabled() { return settings == null || settings.getBool(Settings.CACHE_ENABLED); }

  /** Seconds the cache keeps a value: the admin's setting, else the cache's own default. */
  int ttlSeconds(Name name) {
    return settings == null ? name.ttlSeconds : settings.getInt(Settings.ttlOf(name));
  }

  /** Creates the caches, all empty. */
  public AppCaches(Clock clock) {
    this.clock = clock;
    for (Name n : Name.values()) stores.put(n, new Store());
  }

  /**
   * The cached value for the key, or the loader's result (stored for next time). A loader that throws caches nothing and the
   * exception reaches the caller. A null result is not cached.
   */
  @SuppressWarnings("unchecked")
  public <T> T get(Name name, Object key, Supplier<T> loader) {
    Store s = stores.get(name);
    long now = clock.millis();
    int ttl = ttlSeconds(name);
    if (!enabled() || ttl <= 0) {
      s.bypassed.incrementAndGet();
      return loader.get();
    }
    Entry e = s.map.get(key);
    if (e != null && e.expiresAt() > now) {
      s.hits.incrementAndGet();
      return (T) e.value();
    }
    s.misses.incrementAndGet();
    long t0 = System.nanoTime();
    T loaded = loader.get();
    long ms = (System.nanoTime() - t0) / 1_000_000;
    MeterRegistry r = registry;
    if (r != null) {
      Timer.builder("mall.cache.load").description("Time to load a cache entry from the database").tag("cache", name.key).register(r)
          .record(Duration.ofMillis(ms));
    }
    if (ms >= SLOW_LOAD_MS) {
      log.atWarn().setMessage("Slow cache load cache={} took {}ms").addArgument(name.key).addArgument(ms).addKeyValue("event", "cache-slow-load")
          .addKeyValue("cache", name.key).addKeyValue("durationMs", ms).log();
    } else {
      log.atDebug().setMessage("Cache miss, loaded cache={} in {}ms").addArgument(name.key).addArgument(ms).addKeyValue("event", "cache-load")
          .addKeyValue("cache", name.key).addKeyValue("durationMs", ms).log();
    }
    if (loaded != null) {
      if (s.map.size() >= name.maxEntries) s.map.clear(); // a runaway number of keys must not grow memory
      s.map.put(key, new Entry(loaded, now + ttl * 1000L));
    }
    return loaded;
  }

  /** Empties one cache and counts the clear. */
  public void invalidate(Name name) {
    Store s = stores.get(name);
    int dropped = s.map.size();
    s.map.clear();
    s.clears.incrementAndGet();
    s.lastCleared = clock.millis();
    log.atDebug().setMessage("Cache emptied cache={} entries={}").addArgument(name.key).addArgument(dropped).addKeyValue("event", "cache-clear")
        .addKeyValue("cache", name.key).addKeyValue("entries", dropped).log();
  }

  /** Empties every cache. */
  public void invalidateAll() { for (Name n : Name.values()) invalidate(n); }

  /** Current figures of every cache for the admin screen; a switched-off cache shows a life of 0. */
  public List<Stat> stats() {
    List<Stat> out = new ArrayList<>();
    for (Name n : Name.values()) {
      Store s = stores.get(n);
      out.add(new Stat(n.key, n.description, enabled() ? ttlSeconds(n) : 0, s.map.size(), s.hits.get(), s.misses.get(), s.bypassed.get(),
          s.clears.get(), s.lastCleared == 0 ? null : s.lastCleared));
    }
    return out;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    this.registry = registry;
    for (Name n : Name.values()) {
      Store s = stores.get(n);
      FunctionCounter.builder("mall.cache.hits", s.hits, AtomicLong::doubleValue).description("Cache hits").tag("cache", n.key).register(registry);
      FunctionCounter.builder("mall.cache.misses", s.misses, AtomicLong::doubleValue).description("Cache misses (loaded from the database)")
          .tag("cache", n.key).register(registry);
      FunctionCounter.builder("mall.cache.clears", s.clears, AtomicLong::doubleValue).description("Times the cache was emptied (changes, refresh)")
          .tag("cache", n.key).register(registry);
      FunctionCounter.builder("mall.cache.bypassed", s.bypassed, AtomicLong::doubleValue).description("Reads that skipped the cache (switched off)")
          .tag("cache", n.key).register(registry);
      Gauge.builder("mall.cache.entries", s.map, ConcurrentHashMap::size).description("Values currently cached").tag("cache", n.key).register(registry);
    }
  }
}
