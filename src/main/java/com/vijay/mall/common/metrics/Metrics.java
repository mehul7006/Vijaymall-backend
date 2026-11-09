package com.vijay.mall.common.metrics;

import com.vijay.mall.common.error.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;

/**
 * Small helpers for the business metrics. Every one of them accepts a null registry and then does nothing extra, so plain
 * unit tests (which have no metrics) behave exactly as before.
 */
public final class Metrics {
  private Metrics() {}

  /**
   * Runs {@code body} and records how long it took in the timer {@code name}, tagged outcome=ok, or the ApiException code
   * (for example OUT_OF_STOCK) or "error". The exception, if any, is rethrown unchanged.
   */
  public static <T> T timed(MeterRegistry registry, String name, String description, Supplier<T> body) {
    if (registry == null) return body.get();
    Timer.Sample sample = Timer.start(registry);
    String outcome = "ok";
    try {
      return body.get();
    } catch (RuntimeException e) {
      outcome = e instanceof ApiException a ? a.code() : "error";
      throw e;
    } finally {
      sample.stop(Timer.builder(name).description(description).tag("outcome", outcome).publishPercentileHistogram().register(registry));
    }
  }

  /** Adds {@code amount} to the counter {@code name} with one tag. */
  public static void count(MeterRegistry registry, String name, String description, String tagKey, String tagValue, double amount) {
    if (registry == null) return;
    registry.counter(name, Tags.of(tagKey, tagValue == null ? "none" : tagValue)).increment(amount);
  }
}
