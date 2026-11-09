package com.vijay.mall.flash.app;

import com.vijay.mall.flash.infra.FlashRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Gauges mall.flash.sales{state=active|scheduled}. Read from the database at most every 15 seconds; a failure keeps the last numbers. */
@Component
public class FlashMetrics implements MeterBinder {
  private static final Logger log = LoggerFactory.getLogger(FlashMetrics.class);
  /** How long the database counts are reused, in milliseconds. */
  static final long CACHE_MS = 15_000;

  private final FlashRepository repo;
  private final Clock clock;
  private volatile FlashRepository.StateCounts counts = new FlashRepository.StateCounts(0, 0);
  private volatile long takenAt = Long.MIN_VALUE;

  /** Creates the metrics binder. */
  public FlashMetrics(FlashRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  FlashRepository.StateCounts counts() {
    long now = clock.millis();
    if (takenAt == Long.MIN_VALUE || now - takenAt >= CACHE_MS) {
      try {
        counts = repo.stateCounts(clock.instant());
      } catch (RuntimeException e) {
        log.warn("Could not refresh the flash sale metrics, keeping the last values: {}", e.getMessage());
      }
      takenAt = now;
    }
    return counts;
  }

  /** Registers the two gauges, one for active and one for scheduled sales. */
  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("mall.flash.sales", this, m -> m.counts().active()).description("Flash sales by state").tag("state", "active")
        .strongReference(true).register(registry);
    Gauge.builder("mall.flash.sales", this, m -> m.counts().scheduled()).description("Flash sales by state").tag("state", "scheduled")
        .strongReference(true).register(registry);
  }
}
