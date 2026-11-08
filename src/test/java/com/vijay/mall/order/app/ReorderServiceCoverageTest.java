package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.order.domain.OrderViews;
import com.vijay.mall.order.infra.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The whole-order check of "order again", including what makes allAvailable false. */
class ReorderServiceCoverageTest {
  private OrderRepository orders;
  private CatalogService catalog;
  private ReorderService service;
  private final UUID user = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    orders = mock(OrderRepository.class);
    catalog = mock(CatalogService.class);
    service = new ReorderService(orders, catalog);
    when(orders.find(orderId)).thenReturn(Optional.of(new OrderRepository.Row(orderId, "VSM-3", user, "PICKUP", "DELIVERED", 1,
        null, 0, 0, 0, 0, 1, 0, false, "COD", "CAPTURED", null, null, Instant.now(), 0)));
  }

  private Product product(UUID id, long price, int qty) {
    return new Product(id, UUID.randomUUID(), "P", "P", "", 6000, price, "kg", "1", null, qty > 0, qty, false, true, true, id, 0, 0);
  }

  private OrderViews.Item item(UUID id, int qty) { return new OrderViews.Item(id, "P", qty, 5000, 5000L * qty); }

  @Test
  void everythingInStockIsAllAvailable() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(orders.items(orderId)).thenReturn(List.of(item(a, 2), item(b, 1)));
    when(catalog.visible(anyList())).thenReturn(List.of(product(a, 5000, 9), product(b, 5000, 1)));
    var r = service.check(user, orderId);
    assertThat(r.orderNo()).isEqualTo("VSM-3");
    assertThat(r.items()).hasSize(2).allSatisfy(l -> assertThat(l.reason()).isNull());
    assertThat(r.allAvailable()).isTrue();
  }

  @Test
  void aPartlyAvailableLineStillMeansNotAllAvailable() {
    UUID a = UUID.randomUUID();
    when(orders.items(orderId)).thenReturn(List.of(item(a, 5)));
    when(catalog.visible(anyList())).thenReturn(List.of(product(a, 5000, 3)));
    var r = service.check(user, orderId);
    assertThat(r.items().get(0).available()).isTrue();
    assertThat(r.items().get(0).qty()).isEqualTo(3);
    assertThat(r.items().get(0).reason()).isEqualTo(ReorderService.LOW_STOCK);
    assertThat(r.allAvailable()).isFalse();
  }

  @Test
  void aRemovedProductMakesTheOrderNotAllAvailable() {
    UUID a = UUID.randomUUID();
    UUID gone = UUID.randomUUID();
    when(orders.items(orderId)).thenReturn(List.of(item(a, 1), item(gone, 1), item(gone, 2)));
    when(catalog.visible(anyList())).thenReturn(List.of(product(a, 5000, 9)));
    var r = service.check(user, orderId);
    assertThat(r.items()).extracting(ReorderService.Line::reason).containsExactly(null, ReorderService.UNAVAILABLE, ReorderService.UNAVAILABLE);
    assertThat(r.allAvailable()).isFalse();
  }

  @Test
  void negativeStockCountsAsOutOfStock() {
    var l = ReorderService.line(item(UUID.randomUUID(), 2), product(UUID.randomUUID(), 5000, -4));
    assertThat(l.qty()).isZero();
    assertThat(l.available()).isFalse();
    assertThat(l.reason()).isEqualTo(ReorderService.OUT_OF_STOCK);
  }

  @Test
  void anotherCustomersOrderIsNotFound() {
    assertThatThrownBy(() -> service.check(UUID.randomUUID(), orderId)).isInstanceOf(ApiException.class);
    when(orders.find(UUID.fromString("00000000-0000-0000-0000-000000000001"))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.check(user, UUID.fromString("00000000-0000-0000-0000-000000000001"))).isInstanceOf(ApiException.class);
  }
}
