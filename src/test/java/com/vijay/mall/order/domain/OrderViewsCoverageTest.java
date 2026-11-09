package com.vijay.mall.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The shorter View constructors old callers use fill the newer fields with "not given". */
class OrderViewsCoverageTest {
  private static final Instant AT = Instant.parse("2027-03-01T10:00:00Z");

  @Test
  void viewWithoutTheCoinBalanceHasNullBalance() {
    UUID id = UUID.randomUUID();
    var v = new OrderViews.View(id, "VSM-1", "PLACED", "PICKUP", "COD", "NONE", List.of(), 1000, "C", 100, 5, 50, 20, 870, 3, null,
        null, List.of(new OrderViews.Step("PLACED", AT)), AT, "9-11", "none");
    assertThat(v.coinBalance()).isNull();
    assertThat(v.slot()).isEqualTo("9-11");
    assertThat(v.cancelReason()).isEqualTo("none");
    assertThat(v.totalPaise()).isEqualTo(870);
    assertThat(v.timeline()).hasSize(1);
  }

  @Test
  void viewWithoutSlotReasonOrBalanceLeavesAllThreeNull() {
    var v = new OrderViews.View(UUID.randomUUID(), "VSM-1", "PLACED", "PICKUP", "COD", "NONE", List.of(), 1000, null, 0, 0, 0, 0, 1000, 0,
        null, null, List.of(), AT);
    assertThat(v.slot()).isNull();
    assertThat(v.cancelReason()).isNull();
    assertThat(v.coinBalance()).isNull();
    assertThat(v.placedAt()).isEqualTo(AT);
    assertThat(v.subtotalPaise()).isEqualTo(1000);
  }

  @Test
  void newerFieldsDefaultToNullInTheShorterConstructors() {
    UUID p = UUID.randomUUID();
    var item = new OrderViews.Item(p, "Rice", 2, 50, 100, "Chokha", "kg", 7);
    assertThat(item.couponDiscountPaise()).isEqualTo(7);
    assertThat(item.imageUrl()).isNull();
    var view = new OrderViews.View(UUID.randomUUID(), "VSM-1", "CANCELLED", "PICKUP", "COD", "NONE", List.of(item), 1000, null, 0, 0, 0,
        0, 1000, 0, null, null, List.of(), AT, null, "why", 5, AT);
    assertThat(view.paymentExpiresAt()).isEqualTo(AT);
    assertThat(view.cancelledBy()).isNull();
    assertThat(view.cancelCode()).isNull();
    var summary = new OrderViews.Summary(UUID.randomUUID(), "VSM-1", "CANCELLED", "PICKUP", 100, 1, AT, 100, null, 0, 0, 0, 0, "COD", "NONE");
    assertThat(summary.cancelledBy()).isNull();
    assertThat(summary.cancelCode()).isNull();
    var admin = new OrderViews.AdminSummary(UUID.randomUUID(), "VSM-1", "CANCELLED", "PICKUP", 100, 1, AT, "A", "a@b.c", "9", 100, null,
        0, 0, 0, 0, "COD", "NONE");
    assertThat(admin.cancelledBy()).isNull();
    assertThat(admin.cancelCode()).isNull();
  }

  @Test
  void itemConstructorsDefaultTheMissingFields() {
    UUID p = UUID.randomUUID();
    var five = new OrderViews.Item(p, "Rice", 2, 50, 100);
    assertThat(five.nameGu()).isEqualTo("Rice");
    assertThat(five.unitLabel()).isEmpty();
    assertThat(five.couponDiscountPaise()).isZero();
    var seven = new OrderViews.Item(p, "Rice", 2, 50, 100, "Chokha", "kg");
    assertThat(seven.nameGu()).isEqualTo("Chokha");
    assertThat(seven.unitLabel()).isEqualTo("kg");
    assertThat(seven.couponDiscountPaise()).isZero();
  }
}
