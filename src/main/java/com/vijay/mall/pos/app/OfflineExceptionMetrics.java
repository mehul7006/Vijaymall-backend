package com.vijay.mall.pos.app;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gauges for the offline-bill exceptions waiting for a manager: how many are open, and how many have waited more than a day.
 * They read the database, so values are cached for 15 seconds, and a database hiccup keeps the last good numbers.
 */
@Component
public class OfflineExceptionMetrics implements MeterBinder {
  private static final Logger log = LoggerFactory.getLogger(OfflineExceptionMetrics.class);
  static final long CACHE_MS = 15_000;

  private final OfflineExceptionService exceptions;
  private final Clock clock;
  private volatile long open;
  private volatile long stale;
  private volatile long takenAt = Long.MIN_VALUE;

  /**
   * Creates the gauges.
   *
   * @param exceptions source of the open and stale counts
   * @param clock clock used for the 15 second cache
   */
  public OfflineExceptionMetrics(OfflineExceptionService exceptions, Clock clock) {
    this.exceptions = exceptions;
    this.clock = clock;
  }

  private void refresh() {
    long now = clock.millis();
    if (takenAt != Long.MIN_VALUE && now - takenAt < CACHE_MS) return;
    takenAt = now; // also after a failure, so a struggling database is not hit on every scrape
    try {
      open = exceptions.openCount();
      stale = exceptions.staleCount();
    } catch (RuntimeException e) {
      log.warn("Could not refresh the offline exception metrics, keeping the last values: {}", e.getMessage());
    }
  }

  /** Registers the {@code mall.pos.offline.exceptions.open} and {@code mall.pos.offline.exceptions.stale} gauges. */
  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("mall.pos.offline.exceptions.open", this, m -> { m.refresh(); return m.open; })
        .description("Differences in offline counter bills that no manager has reviewed yet").strongReference(true).register(registry);
    Gauge.builder("mall.pos.offline.exceptions.stale", this, m -> { m.refresh(); return m.stale; })
        .description("Unreviewed offline bill differences older than one day").strongReference(true).register(registry);
  }
}
