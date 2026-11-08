package com.vijay.mall.flash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.flash.app.FlashLifecycle;
import com.vijay.mall.flash.app.FlashMetrics;
import com.vijay.mall.flash.infra.FlashRepository;
import com.vijay.mall.flash.infra.FlashRepository.Lifecycle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FlashLifecycleMetricsTest {
  static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  FlashRepository repo = mock(FlashRepository.class);
  OutboxService outbox = mock(OutboxService.class);
  LiveEvents live = mock(LiveEvents.class);
  Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  FlashLifecycle lifecycle = new FlashLifecycle(repo, outbox, live, clock);
  UUID id = UUID.randomUUID();

  @Test
  void aSaleThatStartedIsAnnouncedAndScreensAreTold() {
    when(repo.claimStarted(NOW)).thenReturn(List.of(new Lifecycle(id, "Diwali", NOW.minusSeconds(30), NOW.plusSeconds(3600), false)));
    when(repo.claimEnded(NOW)).thenReturn(List.of());
    when(repo.items(id)).thenReturn(List.of());
    lifecycle.tick();
    verify(outbox).add(eq("FLASH_STARTED"), any());
    verify(live).area(CatalogChanged.FLASH);
  }

  @Test
  void aSaleThatEndedIsAnnouncedWithItsTotals() {
    when(repo.claimStarted(NOW)).thenReturn(List.of());
    when(repo.claimEnded(NOW)).thenReturn(List.of(new Lifecycle(id, "Diwali", NOW.minusSeconds(3600), NOW.minusSeconds(30), true)));
    when(repo.totals(id)).thenReturn(new FlashRepository.Totals(10, 4, 1, 80000, 20000, 3));
    lifecycle.tick();
    verify(outbox).add(eq("FLASH_ENDED"), any());
    verify(live).area(CatalogChanged.FLASH);
  }

  @Test
  void aSaleThatEndedLongAgoIsMarkedWithoutNotifying() {
    when(repo.claimStarted(NOW)).thenReturn(List.of());
    when(repo.claimEnded(NOW)).thenReturn(List.of(new Lifecycle(id, "Old", NOW.minusSeconds(900000), NOW.minusSeconds(800000), false)));
    when(repo.totals(id)).thenReturn(new FlashRepository.Totals(0, 0, 0, 0, 0, 0));
    lifecycle.tick();
    verify(outbox, never()).add(any(), any());
    verify(live).area(CatalogChanged.FLASH);
  }

  @Test
  void nothingHappenedMeansNoNotificationAndNoBroadcast() {
    when(repo.claimStarted(NOW)).thenReturn(List.of());
    when(repo.claimEnded(NOW)).thenReturn(List.of());
    lifecycle.tick();
    verify(outbox, never()).add(any(), any());
    verify(live, never()).area(any());
  }

  @Test
  void theSaleGaugesReadTheDatabaseOnlyEveryFifteenSeconds() {
    var registry = new SimpleMeterRegistry();
    var clock = new Clock() {
      long ms = NOW.toEpochMilli();
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId z) { return this; }
      @Override public Instant instant() { return Instant.ofEpochMilli(ms); }
    };
    when(repo.stateCounts(any())).thenReturn(new FlashRepository.StateCounts(2, 1));
    FlashMetrics metrics = new FlashMetrics(repo, clock);
    metrics.bindTo(registry);
    assertThat(registry.get("mall.flash.sales").tag("state", "active").gauge().value()).isEqualTo(1.0);
    assertThat(registry.get("mall.flash.sales").tag("state", "scheduled").gauge().value()).isEqualTo(2.0);
    verify(repo, times(1)).stateCounts(any());
    clock.ms += 16_000;
    when(repo.stateCounts(any())).thenThrow(new RuntimeException("db down"));
    assertThat(registry.get("mall.flash.sales").tag("state", "active").gauge().value()).isEqualTo(1.0); // keeps the last numbers
    verify(repo, times(2)).stateCounts(any());
  }
}
