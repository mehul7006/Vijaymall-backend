package com.vijay.mall.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.ReferenceNumbers;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.catalog.infra.BannerRepository;
import com.vijay.mall.catalog.infra.CategoryRepository;
import com.vijay.mall.catalog.infra.ProductRepository;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository.Stock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The product search results (which products, in what order, how many) are kept for a few seconds; stock is always read fresh. */
class CatalogSearchCacheTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private final UUID cat = UUID.randomUUID();
  private final UUID pid = UUID.randomUUID();
  private ProductRepository products;
  private InventoryService inventory;
  private CatalogService service;
  private AppCaches caches;
  private int stockOnHand = 7;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    products = mock(ProductRepository.class);
    inventory = mock(InventoryService.class);
    service = new CatalogService(products, mock(CategoryRepository.class), mock(BannerRepository.class), inventory, mock(AuditService.class),
        Clock.fixed(NOW, ZoneOffset.UTC), mock(ReferenceNumbers.class));
    caches = new AppCaches(Clock.fixed(NOW, ZoneOffset.UTC));
    service.setCaches(caches);
    when(inventory.stocks(any())).thenAnswer(inv -> {
      Map<UUID, Stock> out = new HashMap<>();
      for (UUID id : (Collection<UUID>) inv.getArgument(0)) out.put(id, new Stock(id, stockOnHand, 5));
      return out;
    });
  }

  private ProductData product() {
    return new ProductData(pid, cat, "Rice", "ચોખા", "d", 6000, 5000, "kg", "1 kg", "/img/x_400.jpg", true, false, null, null, true, false);
  }

  @Test
  void aRepeatedShopListDoesNotSearchAgainButStockIsFresh() {
    when(products.searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(product()), 0, 20, 1));
    assertThat(service.list("rice", cat, false, 0, 20).items().get(0).qtyAvailable()).isEqualTo(7);
    stockOnHand = 2;
    assertThat(service.list("rice", cat, false, 0, 20).items().get(0).qtyAvailable()).as("the list is remembered, the stock is not").isEqualTo(2);
    verify(products, times(1)).searchInStock("rice", cat, false, NOW, 0, 20);
    service.list("rice", cat, false, 1, 20); // another page is another search
    verify(products, times(2)).searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt());
  }

  @Test
  void anAdminEditOrAHardRefreshMakesTheNextListSearchAgain() {
    when(products.searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(product()), 0, 20, 1));
    service.list(null, null, false, 0, 20);
    caches.invalidate(AppCaches.Name.PRODUCT_LIST);
    service.list(null, null, false, 0, 20);
    verify(products, times(2)).searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt());
  }

  @Test
  void outOfStockListsAreSeparateKeysAndHotListsAreNeverRemembered() {
    when(products.search(any(), any(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(product()), 0, 20, 1));
    when(products.searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(product()), 0, 20, 1));
    service.list(null, null, false, true, 0, 20);
    service.list(null, null, false, true, 0, 20); // remembered
    service.list(null, null, false, false, 0, 20);
    service.list(null, null, true, false, 0, 20);
    service.list(null, null, true, false, 0, 20); // "on hot sale now" depends on the clock: asked every time
    verify(products, times(1)).search(any(), any(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt());
    verify(products, times(3)).searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt());
  }

  @Test
  void groupAndAdminSearchesAreRememberedToo() {
    UUID group = UUID.randomUUID();
    when(products.searchGroups(any(), any(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(group), 0, 20, 1));
    when(products.visibleMembers(any())).thenReturn(List.of());
    service.groups(null, null, false, 0, 20);
    service.groups(null, null, false, 0, 20);
    verify(products, times(1)).searchGroups(any(), any(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt());
    when(products.search(any(), any(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(product()), 0, 20, 1));
    service.adminList("rice", null, 0, 20);
    service.adminList("rice", null, 0, 20);
    verify(products, times(1)).search(any(), any(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt());
  }

  @Test
  void withoutACacheEverySearchHitsTheDatabase() {
    service.setCaches(null);
    when(products.searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(product()), 0, 20, 1));
    service.list(null, null, false, 0, 20);
    service.list(null, null, false, 0, 20);
    verify(products, times(2)).searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt());
  }
}
