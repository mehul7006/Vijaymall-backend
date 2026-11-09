package com.vijay.mall.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.purchasing.app.StockTakeService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/** Stock take rules (the purchase rules are tested in PurchaseServiceTest and the purchasing integration tests). */
class PurchasingServicesTest {
  private final UUID actor = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();
  private final UUID dal = UUID.randomUUID();
  private InventoryService inventory;
  private CatalogService catalog;
  private AuditService audit;
  private StockTakeService stockTake;

  @BeforeEach
  void setUp() {
    inventory = mock(InventoryService.class);
    catalog = mock(CatalogService.class);
    audit = mock(AuditService.class);
    stockTake = new StockTakeService(inventory, catalog, audit);
    when(catalog.names(any())).thenAnswer(i -> {
      var out = new HashMap<UUID, String>();
      for (Object o : (Collection<?>) i.getArgument(0)) {
        if (o.equals(rice)) out.put(rice, "Rice");
        if (o.equals(dal)) out.put(dal, "Dal");
      }
      return out;
    });
  }

  // ---------- stock take ----------
  @Test
  void aCountSetsStockToWhatWasFoundAndReportsOnlyTheDifferences() {
    when(inventory.quantityForUpdate(rice)).thenReturn(50);
    when(inventory.quantityForUpdate(dal)).thenReturn(10);
    var r = stockTake.apply(actor, " month end ", List.of(new StockTakeService.Count(rice, 47), new StockTakeService.Count(dal, 10)));
    verify(inventory).adjust(rice, -3, "STOCK_TAKE", actor);
    verify(inventory, never()).adjust(eq(dal), anyInt(), anyString(), any());
    assertThat(r.unchanged()).isEqualTo(1);
    assertThat(r.changes()).singleElement().satisfies(c -> {
      assertThat(c.name()).isEqualTo("Rice");
      assertThat(c.before()).isEqualTo(50);
      assertThat(c.counted()).isEqualTo(47);
      assertThat(c.delta()).isEqualTo(-3);
    });
    verify(audit).log(eq(actor), eq("STOCK_TAKE"), eq("inventory"), eq(null), ArgumentMatchers.contains("1 changed, 1 matched"));
  }

  @Test
  void aCountHigherThanTheSystemAddsStock() {
    when(inventory.quantityForUpdate(rice)).thenReturn(5);
    stockTake.apply(actor, null, List.of(new StockTakeService.Count(rice, 9)));
    verify(inventory).adjust(rice, 4, "STOCK_TAKE", actor);
  }

  @Test
  void badCountsAreRefused() {
    assertThatThrownBy(() -> stockTake.apply(actor, "", null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> stockTake.apply(actor, "", List.of())).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> stockTake.apply(actor, "", List.of(new StockTakeService.Count(null, 1)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> stockTake.apply(actor, "", List.of(new StockTakeService.Count(rice, 1), new StockTakeService.Count(rice, 2))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> stockTake.apply(actor, "", List.of(new StockTakeService.Count(rice, -1)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> stockTake.apply(actor, "", List.of(new StockTakeService.Count(UUID.randomUUID(), 1)))).isInstanceOf(ApiException.class);
    var many = IntStream.range(0, StockTakeService.MAX_LINES + 1).mapToObj(i -> new StockTakeService.Count(UUID.randomUUID(), 1)).toList();
    assertThatThrownBy(() -> stockTake.apply(actor, "", many)).isInstanceOf(ApiException.class);
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any());
  }
}
