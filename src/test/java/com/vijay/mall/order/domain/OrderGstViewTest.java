package com.vijay.mall.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.tax.GstRates;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderGstViewTest {
  private static OrderViews.Item item(String rate, Long taxable, Long cgst, Long sgst) {
    return new OrderViews.Item(UUID.randomUUID(), "p", 1, 100, 100, "p", "", 0, null, rate == null ? null : GstRates.of(rate), "h", taxable, cgst, sgst);
  }

  @Test
  void recordedLinesAreGroupedByRate() {
    var gst = OrderViews.Gst.ofItems(List.of(item("18", 10000L, 900L, 900L), item("5", 100L, 2L, 3L), item("18", 100L, 9L, 9L)));
    assertThat(gst.gstRecorded()).isTrue();
    assertThat(gst.taxableTotalPaise()).isEqualTo(10200);
    assertThat(gst.cgstTotalPaise()).isEqualTo(911);
    assertThat(gst.sgstTotalPaise()).isEqualTo(912);
    assertThat(gst.taxTotalPaise()).isEqualTo(1823);
    assertThat(gst.gstSlabs()).hasSize(2);
    assertThat(gst.gstSlabs().get(0).gstPercent()).isEqualByComparingTo("5");
  }

  @Test
  void anOrderWithAnUnrecordedLineOrNoLinesIsNotRecorded() {
    for (var items : List.of(List.<OrderViews.Item>of(), List.of(item(null, null, null, null)), List.of(item("5", 1L, 0L, 0L), item(null, null, null, null)))) {
      var gst = OrderViews.Gst.ofItems(items);
      assertThat(gst).isSameAs(OrderViews.Gst.NOT_RECORDED);
      assertThat(gst.gstRecorded()).isFalse();
      assertThat(gst.taxableTotalPaise()).isNull();
      assertThat(gst.gstSlabs()).isEmpty();
    }
  }

  @Test
  void theOlderConstructorsReadAsNotRecorded() {
    var item = new OrderViews.Item(UUID.randomUUID(), "p", 1, 100, 100, "p", "", 0, "img");
    assertThat(item.gstPercent()).isNull();
    assertThat(item.taxablePaise()).isNull();
    var summary = new OrderViews.Summary(UUID.randomUUID(), "N", "PLACED", "PICKUP", 1, 1, null, 1, null, 0, 0, 0, 0, "COD", "NONE", null, null);
    assertThat(summary.gst()).isSameAs(OrderViews.Gst.NOT_RECORDED);
    var admin = new OrderViews.AdminSummary(UUID.randomUUID(), "N", "PLACED", "PICKUP", 1, 1, null, "n", "e", "m", 1, null, 0, 0, 0, 0, "COD", "NONE", null, null);
    assertThat(admin.gst()).isSameAs(OrderViews.Gst.NOT_RECORDED);
    var view = new OrderViews.View(UUID.randomUUID(), "N", "PLACED", "PICKUP", "COD", "NONE", List.of(), 1, null, 0, 0, 0, 0, 1, 0, null, null, List.of(),
        null, null, null, null, null, null, null);
    assertThat(view.gst()).isSameAs(OrderViews.Gst.NOT_RECORDED);
  }
}
