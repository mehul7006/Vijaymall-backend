package com.vijay.mall.ops;

import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.domain.OrderStatus;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.payment.infra.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gauges that answer "is the shop's back office healthy right now": refunds waiting or failed, events stuck in the outbox,
 * orders in each status, products low on stock. They read the database, so values are cached for 15 seconds (one scrape,
 * not one query per gauge) and a database hiccup keeps the last good numbers instead of breaking the metrics endpoint.
 */
@Component
public class OpsMetrics implements MeterBinder {
  private static final Logger log = LoggerFactory.getLogger(OpsMetrics.class);
  static final long CACHE_MS = 15_000;

  record Snapshot(long refundsPending, long refundsFailed, long outboxPending, long lowStock, Map<String, Long> ordersByStatus) {}

  private final PaymentRepository payments;
  private final OrderRepository orders;
  private final OutboxService outbox;
  private final InventoryService inventory;
  private final Clock clock;
  private volatile Snapshot snapshot = new Snapshot(0, 0, 0, 0, Map.of());
  private volatile long takenAt = Long.MIN_VALUE;

  public OpsMetrics(PaymentRepository payments, OrderRepository orders, OutboxService outbox, InventoryService inventory, Clock clock) {
    this.payments = payments;
    this.orders = orders;
    this.outbox = outbox;
    this.inventory = inventory;
    this.clock = clock;
  }

  Snapshot snapshot() {
    long now = clock.millis();
    if (takenAt == Long.MIN_VALUE || now - takenAt >= CACHE_MS) {
      try {
        snapshot = new Snapshot(payments.countRefunds("PENDING") + payments.countRefunds("PROCESSING"), payments.countRefunds("FAILED"),
            outbox.pending(), inventory.lowStockCount(), orders.countByStatus());
        takenAt = now;
      } catch (RuntimeException e) {
        log.warn("Could not refresh the operations metrics, keeping the last values: {}", e.getMessage());
        takenAt = now; // do not hammer a struggling database on every scrape
      }
    }
    return snapshot;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    gauge(registry, "mall.refunds.pending", "Refunds queued or being sent to the payment provider", () -> snapshot().refundsPending());
    gauge(registry, "mall.refunds.failed", "Refunds the provider refused after all tries: a person must look", () -> snapshot().refundsFailed());
    gauge(registry, "mall.outbox.pending", "Events written but not yet delivered (notifications, alerts)", () -> snapshot().outboxPending());
    gauge(registry, "mall.stock.low.products", "Products at or below their stock alert level", () -> snapshot().lowStock());
    for (OrderStatus s : OrderStatus.values()) {
      Gauge.builder("mall.orders.by.status", this, m -> m.snapshot().ordersByStatus().getOrDefault(s.name(), 0L))
          .description("Online orders currently in each status").tag("status", s.name()).strongReference(true).register(registry);
    }
  }

  private void gauge(MeterRegistry registry, String name, String description, java.util.function.LongSupplier value) {
    Gauge.builder(name, value, v -> v.getAsLong()).description(description).strongReference(true).register(registry);
  }
}
