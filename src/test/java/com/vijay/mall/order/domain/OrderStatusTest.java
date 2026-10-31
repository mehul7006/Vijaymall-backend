package com.vijay.mall.order.domain;

import static com.vijay.mall.order.domain.Fulfilment.*;
import static com.vijay.mall.order.domain.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {
  @Test void happy_path_delivery() {
    assertThat(PLACED.next(DELIVERY)).containsExactlyInAnyOrder(CONFIRMED, CANCELLED);
    assertThat(CONFIRMED.next(DELIVERY)).containsExactlyInAnyOrder(PACKED, CANCELLED);
    assertThat(PACKED.next(DELIVERY)).containsExactlyInAnyOrder(OUT_FOR_DELIVERY, CANCELLED);
    assertThat(OUT_FOR_DELIVERY.next(DELIVERY)).containsExactly(DELIVERED);
  }
  @Test void happy_path_pickup() {
    assertThat(PACKED.next(PICKUP)).containsExactlyInAnyOrder(READY_FOR_PICKUP, CANCELLED);
    assertThat(READY_FOR_PICKUP.next(PICKUP)).containsExactly(PICKED_UP);
  }
  @Test void online_payment_starts_pending() {
    assertThat(PAYMENT_PENDING.next(DELIVERY)).containsExactlyInAnyOrder(PLACED, CANCELLED);
  }
  @Test void final_states_have_no_moves() {
    for (var s : new OrderStatus[] {DELIVERED, PICKED_UP, CANCELLED}) {
      assertThat(s.next(DELIVERY)).isEmpty();
      assertThat(s.isFinal()).isTrue();
    }
  }
  @Test void cannot_skip_steps() {
    assertThat(PLACED.canMoveTo(PACKED, DELIVERY)).isFalse();
    assertThat(PACKED.canMoveTo(DELIVERED, DELIVERY)).isFalse();
    assertThat(PACKED.canMoveTo(READY_FOR_PICKUP, DELIVERY)).isFalse();
  }
  @Test void customer_can_cancel_only_before_packed() {
    assertThat(PLACED.customerCanCancel()).isTrue();
    assertThat(CONFIRMED.customerCanCancel()).isTrue();
    assertThat(PACKED.customerCanCancel()).isFalse();
    assertThat(OUT_FOR_DELIVERY.customerCanCancel()).isFalse();
  }
  @Test void coins_are_credited_only_on_completion() {
    assertThat(DELIVERED.completesOrder()).isTrue();
    assertThat(PICKED_UP.completesOrder()).isTrue();
    assertThat(PACKED.completesOrder()).isFalse();
  }
}
