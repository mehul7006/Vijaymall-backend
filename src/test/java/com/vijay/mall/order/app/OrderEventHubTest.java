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
}
