package com.vijay.mall.order.domain;

import static com.vijay.mall.order.domain.Fulfilment.*;
import static com.vijay.mall.order.domain.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {
  @Test void open_orders_may_move_to_any_other_status() {
    for (var from : new OrderStatus[] {PLACED, CONFIRMED, PACKED, OUT_FOR_DELIVERY, READY_FOR_PICKUP}) {
      for (var f : Fulfilment.values()) {
        assertThat(from.next(f)).doesNotContain(from, PAYMENT_PENDING)
            .contains(PLACED == from ? CONFIRMED : PLACED, DELIVERED, PICKED_UP, CANCELLED);
      }
    }
  }
  @Test void payment_pending_only_moves_on_payment_or_cancel() {
    assertThat(PAYMENT_PENDING.next(DELIVERY)).containsExactlyInAnyOrder(PLACED, CANCELLED);
  }
  @Test void stock_is_held_from_confirmed_onwards() {
    for (var s : new OrderStatus[] {PAYMENT_PENDING, PLACED, CANCELLED}) assertThat(s.holdsStock()).isFalse();
    for (var s : new OrderStatus[] {CONFIRMED, PACKED, OUT_FOR_DELIVERY, READY_FOR_PICKUP, DELIVERED, PICKED_UP}) {
      assertThat(s.holdsStock()).isTrue();
    }
  }
  @Test void final_states_have_no_moves() {
    for (var s : new OrderStatus[] {DELIVERED, PICKED_UP, CANCELLED}) {
      assertThat(s.next(DELIVERY)).isEmpty();
      assertThat(s.isFinal()).isTrue();
    }
  }
  @Test void sequence_is_not_enforced() {
    assertThat(PLACED.canMoveTo(PACKED, DELIVERY)).isTrue();
    assertThat(PACKED.canMoveTo(DELIVERED, DELIVERY)).isTrue();
    assertThat(PACKED.canMoveTo(CONFIRMED, DELIVERY)).isTrue();
    assertThat(PLACED.canMoveTo(PLACED, DELIVERY)).isFalse();
    assertThat(PLACED.canMoveTo(PAYMENT_PENDING, DELIVERY)).isFalse();
    assertThat(DELIVERED.canMoveTo(PACKED, DELIVERY)).isFalse();
  }
  @Test void statuses_fit_only_their_own_fulfilment() {
    for (var s : new OrderStatus[] {OUT_FOR_DELIVERY, DELIVERED}) {
      assertThat(s.fitsFulfilment(DELIVERY)).isTrue();
      assertThat(s.fitsFulfilment(PICKUP)).isFalse();
    }
    for (var s : new OrderStatus[] {READY_FOR_PICKUP, PICKED_UP}) {
      assertThat(s.fitsFulfilment(PICKUP)).isTrue();
      assertThat(s.fitsFulfilment(DELIVERY)).isFalse();
    }
    for (var s : new OrderStatus[] {PLACED, CONFIRMED, PACKED, CANCELLED}) {
      assertThat(s.fitsFulfilment(PICKUP)).isTrue();
      assertThat(s.fitsFulfilment(DELIVERY)).isTrue();
    }
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
