package com.vijay.mall.order.app.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubtotalLotCapTest {
  private final UUID product = UUID.randomUUID();
  private final CatalogService catalog = mock(CatalogService.class);
  private final InventoryService inventory = mock(InventoryService.class);

  private QuoteContext ctx(int qty, UUID flash) {
    var req = new QuoteRequest(List.of(new CatalogService.ItemRequest(product, qty)), null, null, null, Fulfilment.PICKUP);
    when(catalog.price(any())).thenReturn(List.of(new PricedLine(product, UUID.randomUUID(), "Rice", qty, 100, 100L * qty, true, true, "Rice", "", flash, 100)));
    return new QuoteContext(UUID.randomUUID(), req);
  }

  @Test
  void anOrderBeyondTheCurrentPriceIsRefusedWithHowManyCanBeOrdered() {
    SubtotalStep step = new SubtotalStep(catalog);
    step.setInventory(inventory);
    when(inventory.unitsAtThisPrice(any())).thenReturn(Map.of(product, 3));
    step.apply(ctx(3, null));
    assertThatThrownBy(() -> step.apply(ctx(4, null))).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("QTY_EXCEEDS_LOT_PRICE");
      assertThat(e.getMessage()).contains("Only 3 available at this price");
      assertThat(e.details()).containsEntry("maxQty", 3);
    });
    step.apply(ctx(4, UUID.randomUUID())); // units on a flash sale sell at the sale price whatever the lot
    when(inventory.unitsAtThisPrice(any())).thenReturn(Map.of());
    step.apply(ctx(40, null));
  }
}
