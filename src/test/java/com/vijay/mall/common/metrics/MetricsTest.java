package com.vijay.mall.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MetricsTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void aSuccessfulCallIsTimedAsOk() {
    assertThat(Metrics.timed(registry, "mall.test", "d", () -> 42)).isEqualTo(42);
    assertThat(registry.get("mall.test").tag("outcome", "ok").timer().count()).isEqualTo(1);
  }

  @Test
  void aRefusedCallIsTimedWithTheErrorCodeAndStillThrows() {
    assertThatThrownBy(() -> Metrics.timed(registry, "mall.test", "d", () -> { throw ApiException.conflict("OUT_OF_STOCK", "x", java.util.Map.of()); }))
        .isInstanceOf(ApiException.class);
    assertThat(registry.get("mall.test").tag("outcome", "OUT_OF_STOCK").timer().count()).isEqualTo(1);
  }

  @Test
  void anUnexpectedFailureIsTimedAsError() {
    assertThatThrownBy(() -> Metrics.timed(registry, "mall.test", "d", () -> { throw new IllegalStateException("boom"); }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(registry.get("mall.test").tag("outcome", "error").timer().count()).isEqualTo(1);
  }

  @Test
  void withoutARegistryTheBodyJustRuns() {
    assertThat(Metrics.timed(null, "mall.test", "d", () -> "ran")).isEqualTo("ran");
    Metrics.count(null, "mall.test.count", "d", "k", "v", 1); // no registry: nothing happens, nothing breaks
  }

  @Test
  void countersAddUpPerTagAndAMissingTagBecomesNone() {
    Metrics.count(registry, "mall.things", "d", "kind", "A", 2);
    Metrics.count(registry, "mall.things", "d", "kind", "A", 3);
    Metrics.count(registry, "mall.things", "d", "kind", null, 1);
    assertThat(registry.get("mall.things").tag("kind", "A").counter().count()).isEqualTo(5);
    assertThat(registry.get("mall.things").tag("kind", "none").counter().count()).isEqualTo(1);
  }
}
