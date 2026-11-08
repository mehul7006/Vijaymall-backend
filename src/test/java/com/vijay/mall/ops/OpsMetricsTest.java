package com.vijay.mall.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.payment.infra.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpsMetricsTest {
  private PaymentRepository payments;
  private OrderRepository orders;
  private OutboxService outbox;
  private InventoryService inventory;
  private final java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-08T10:00:00Z"));
  private OpsMetrics metrics;
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @BeforeEach
  void setUp() {
    payments = mock(PaymentRepository.class);
    orders = mock(OrderRepository.class);
    outbox = mock(OutboxService.class);
    inventory = mock(InventoryService.class);
    Clock clock = new Clock() {
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return this; }
      @Override public Instant instant() { return now.get(); }
    };
    metrics = new OpsMetrics(payments, orders, outbox, inventory, clock);
    when(payments.countRefunds("PENDING")).thenReturn(2L);
    when(payments.countRefunds("PROCESSING")).thenReturn(1L);
    when(payments.countRefunds("FAILED")).thenReturn(4L);
    when(outbox.pending()).thenReturn(7L);
    when(inventory.lowStockCount()).thenReturn(5L);
    when(orders.countByStatus()).thenReturn(Map.of("PLACED", 3L, "CONFIRMED", 2L));
    metrics.bindTo(registry);
  }

  private double value(String name) { return registry.get(name).gauge().value(); }

  @Test
  void gaugesShowTheBackOfficeState() {
    assertThat(value("mall.refunds.pending")).isEqualTo(3);
    assertThat(value("mall.refunds.failed")).isEqualTo(4);
    assertThat(value("mall.outbox.pending")).isEqualTo(7);
    assertThat(value("mall.stock.low.products")).isEqualTo(5);
    assertThat(registry.get("mall.orders.by.status").tag("status", "PLACED").gauge().value()).isEqualTo(3);
    assertThat(registry.get("mall.orders.by.status").tag("status", "CANCELLED").gauge().value()).isZero();
  }

  @Test
  void oneScrapeIsOneRoundOfQueriesAndTheValuesAreCachedForAWhile() {
    value("mall.refunds.pending");
    value("mall.refunds.failed");
    value("mall.outbox.pending");
    verify(outbox, times(1)).pending();
    now.set(now.get().plus(Duration.ofSeconds(20)));
    value("mall.outbox.pending");
    verify(outbox, times(2)).pending();
  }

  @Test
  void aDatabaseFailureKeepsTheLastGoodValuesInsteadOfBreakingTheEndpoint() {
    assertThat(value("mall.outbox.pending")).isEqualTo(7);
    now.set(now.get().plus(Duration.ofSeconds(20)));
    when(outbox.pending()).thenThrow(new IllegalStateException("db down"));
    assertThat(value("mall.outbox.pending")).isEqualTo(7);
    verify(outbox, times(2)).pending();
    value("mall.refunds.failed"); // same moment: not asked again
    verify(outbox, times(2)).pending();
  }
}
