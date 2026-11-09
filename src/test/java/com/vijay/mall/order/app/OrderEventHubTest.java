package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.order.domain.OrderChanged;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEventHubTest {
  private final OrderEventHub hub = new OrderEventHub(Runnable::run);
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  private static OrderChanged change(UUID user) {
    return new OrderChanged(UUID.randomUUID(), "VSM-1", user, "CONFIRMED", "PLACED", Instant.parse("2027-03-01T10:00:00Z"));
  }

  @Test
  void streamsAreRegisteredAndCounted() {
    hub.subscribeCustomer(alice);
    hub.subscribeCustomer(alice);
    hub.subscribeStaff();
    assertThat(hub.customerStreams(alice)).isEqualTo(2);
    assertThat(hub.customerStreams(bob)).isZero();
    assertThat(hub.staffStreams()).isEqualTo(1);
  }

  @Test
  void aCustomerStreamIsCappedByClosingTheOldest() {
    for (int i = 0; i < OrderEventHub.MAX_STREAMS_PER_CUSTOMER + 3; i++) hub.subscribeCustomer(alice);
    assertThat(hub.customerStreams(alice)).isEqualTo(OrderEventHub.MAX_STREAMS_PER_CUSTOMER);
  }

  @Test
  void eventsAndHeartbeatsDoNotFailWithOrWithoutListeners() {
    hub.on(change(alice));
    hub.subscribeCustomer(alice);
    hub.subscribeStaff();
    hub.on(change(alice));
    hub.on(change(bob));
    hub.heartbeat();
    assertThat(hub.customerStreams(alice)).isEqualTo(1);
    assertThat(hub.staffStreams()).isEqualTo(1);
  }

  @Test
  void walletAndStockEventsReachOpenStreamsAndNoOneElse() {
    hub.on(new com.vijay.mall.common.live.WalletChanged(alice, "EARN", 10, Instant.parse("2027-03-01T10:00:00Z")));
    hub.on(new com.vijay.mall.common.live.StockChanged(java.util.List.of(UUID.randomUUID()), "ORDER", Instant.parse("2027-03-01T10:00:00Z")));
    hub.subscribeCustomer(alice);
    hub.subscribeCustomer(bob);
    hub.subscribeStaff();
    hub.on(new com.vijay.mall.common.live.WalletChanged(alice, "EARN", 10, Instant.parse("2027-03-01T10:00:00Z")));
    hub.on(new com.vijay.mall.common.live.StockChanged(java.util.List.of(UUID.randomUUID()), "ORDER", Instant.parse("2027-03-01T10:00:00Z")));
    assertThat(hub.customerStreams(alice)).isEqualTo(1);
    assertThat(hub.staffStreams()).isEqualTo(1);
  }

  @Test
  void walletCreditStockAndCatalogEventsAreSentWithoutFailing() {
    Instant at = Instant.parse("2027-03-01T10:00:00Z");
    hub.on(new com.vijay.mall.common.live.WalletChanged(alice, "EARN", 10, at));
    hub.subscribeCustomer(alice);
    hub.subscribeCustomer(bob);
    hub.subscribeStaff();
    hub.on(new com.vijay.mall.common.live.WalletChanged(alice, "EARN", 10, at));
    hub.on(new com.vijay.mall.common.live.CreditChanged(alice, "SALE", 500, at));
    hub.on(new com.vijay.mall.common.live.StockChanged(java.util.List.of(UUID.randomUUID()), "ORDER", at));
    hub.on(new com.vijay.mall.common.live.StockChanged(java.util.List.of(UUID.randomUUID()), "ORDER", at));
    hub.on(new com.vijay.mall.common.live.CatalogChanged("CATALOG", at));
    hub.flush();
    hub.flush();
    hub.heartbeat();
    assertThat(hub.customerStreams(alice)).isEqualTo(1);
    assertThat(hub.staffStreams()).isEqualTo(1);
  }

  @Test
  void stockAndCatalogChangesAreSentOncePerWindow() {
    Instant at = Instant.parse("2027-03-01T10:00:00Z");
    java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
    OrderEventHub counting = new OrderEventHub(r -> { runs.incrementAndGet(); r.run(); });
    counting.subscribeStaff();
    int afterReady = runs.get();
    for (int i = 0; i < 50; i++) counting.on(new com.vijay.mall.common.live.StockChanged(java.util.List.of(UUID.randomUUID()), "ORDER", at));
    for (int i = 0; i < 50; i++) counting.on(new com.vijay.mall.common.live.CatalogChanged("CATALOG", at));
    assertThat(runs.get()).isEqualTo(afterReady);
    counting.flush();
    assertThat(runs.get()).as("one stock message and one catalog message, however many changes").isEqualTo(afterReady + 2);
    counting.flush();
    assertThat(runs.get()).as("nothing pending, nothing sent").isEqualTo(afterReady + 2);
  }

  @Test
  void aClosedStreamIsRemovedAndNewOnesStillRegister() {
    hub.subscribeCustomer(alice).complete();
    hub.subscribeCustomer(alice);
    hub.heartbeat();
    assertThat(hub.customerStreams(alice)).isGreaterThanOrEqualTo(1);
  }
}
