package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OfflineExceptionMetricsTest {
  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-08T10:00:00Z"));
  private OfflineExceptionService exceptions;
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @BeforeEach
  void setUp() {
    exceptions = mock(OfflineExceptionService.class);
    Clock clock = new Clock() {
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return this; }
      @Override public Instant instant() { return now.get(); }
    };
    when(exceptions.openCount()).thenReturn(3L);
    when(exceptions.staleCount()).thenReturn(1L);
    new OfflineExceptionMetrics(exceptions, clock).bindTo(registry);
  }

  private double open() { return registry.get("mall.pos.offline.exceptions.open").gauge().value(); }

  private double stale() { return registry.get("mall.pos.offline.exceptions.stale").gauge().value(); }

  @Test
  void gaugesShowOpenAndStaleExceptionsAndAreCachedForAWhile() {
    assertThat(open()).isEqualTo(3);
    assertThat(stale()).isEqualTo(1);
    verify(exceptions, times(1)).openCount();
    now.set(now.get().plusSeconds(10));
    when(exceptions.openCount()).thenReturn(9L);
    assertThat(open()).as("still cached").isEqualTo(3);
    now.set(now.get().plusSeconds(10));
    assertThat(open()).isEqualTo(9);
  }

  @Test
  void aDatabaseFailureKeepsTheLastValues() {
    assertThat(open()).isEqualTo(3);
    now.set(now.get().plusSeconds(60));
    when(exceptions.openCount()).thenThrow(new IllegalStateException("db down"));
    assertThat(open()).isEqualTo(3);
    assertThat(stale()).isEqualTo(1);
  }
}
