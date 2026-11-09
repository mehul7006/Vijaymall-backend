package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.order.domain.OrderViews;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReorderServiceTest {
  private final UUID id = UUID.randomUUID();
  private final OrderViews.Item item = new OrderViews.Item(id, "Rice", 3, 5000, 15000, "ચોખા", "1 kg");

  private Product product(long price, int qty) {
    return new Product(id, UUID.randomUUID(), "Rice", "ચોખા", "", 6000, price, "kg", "1", null, qty > 0, qty, false, true, true,
        id, 0, 0);
  }

  @Test
  void fullyAvailableLineHasNoReason() {
    var l = ReorderService.line(item, product(5000, 10));
    assertThat(l.available()).isTrue();
    assertThat(l.qty()).isEqualTo(3);
    assertThat(l.reason()).isNull();
    assertThat(l.priceChanged()).isFalse();
  }

  @Test
  void partialStockIsCappedAndFlagged() {
    var l = ReorderService.line(item, product(5000, 2));
    assertThat(l.qty()).isEqualTo(2);
    assertThat(l.available()).isTrue();
    assertThat(l.reason()).isEqualTo(ReorderService.LOW_STOCK);
  }

  @Test
  void theLinePictureIsTheLiveOneOrElseTheOneKeptOnTheOrder() {
    var kept = new OrderViews.Item(id, "Rice", 3, 5000, 15000, "ચોખા", "1 kg", 0, "/old.png");
    var live = new Product(id, UUID.randomUUID(), "Rice", "ચોખા", "", 6000, 5000, "kg", "1", "/new.png", true, 10, false, true, true,
        id, 0, 0);
    assertThat(ReorderService.line(kept, live).imageUrl()).isEqualTo("/new.png");
    assertThat(ReorderService.line(kept, product(5000, 10)).imageUrl()).as("live product has no picture").isEqualTo("/old.png");
    assertThat(ReorderService.line(kept, null).imageUrl()).as("hidden product").isEqualTo("/old.png");
    var shorter = new ReorderService.Line(id, "Rice", "R", "kg", 1, 1, 5, false, true, null);
    assertThat(shorter.imageUrl()).isNull();
  }

  @Test
  void noStockOrHiddenProductIsUnavailable() {
    var none = ReorderService.line(item, product(5000, 0));
    assertThat(none.available()).isFalse();
    assertThat(none.reason()).isEqualTo(ReorderService.OUT_OF_STOCK);
    var gone = ReorderService.line(item, null);
    assertThat(gone.available()).isFalse();
    assertThat(gone.qty()).isZero();
    assertThat(gone.reason()).isEqualTo(ReorderService.UNAVAILABLE);
  }

  @Test
  void aChangedPriceIsReportedWithTodaysPrice() {
    var l = ReorderService.line(item, product(5500, 10));
    assertThat(l.priceChanged()).isTrue();
    assertThat(l.unitPricePaise()).isEqualTo(5500);
  }
}
