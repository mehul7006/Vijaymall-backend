package com.vijay.mall.wishlist.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.wishlist.infra.WishlistRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WishlistServiceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");

  private final WishlistRepository repo = mock(WishlistRepository.class);
  private final CatalogService catalog = mock(CatalogService.class);
  private final WishlistService service = new WishlistService(repo, catalog, Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID user = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();

  @Test
  void addChecksTheProductIsVisibleThenSaves() {
    service.add(user, product);
    verify(catalog).get(product);
    verify(repo).add(user, product, NOW);
  }

  @Test
  void addingAHiddenProductIsNotFoundAndSavesNothing() {
    when(catalog.get(product)).thenThrow(ApiException.notFound("Product"));
    assertThatThrownBy(() -> service.add(user, product)).isInstanceOf(ApiException.class);
    verify(repo, never()).add(any(), any(), any());
  }

  @Test
  void aFullListRefusesNewItemsButAllowsSavingOneAgain() {
    when(repo.count(user)).thenReturn((long) WishlistService.MAX_ITEMS);
    assertThatThrownBy(() -> service.add(user, product)).isInstanceOf(ApiException.class);
    verify(repo, never()).add(any(), any(), any());

    when(repo.contains(user, product)).thenReturn(true);
    service.add(user, product);
    verify(repo).add(user, product, NOW);
  }

  @Test
  void listReturnsVisibleProductsInSavedOrder() {
    UUID second = UUID.randomUUID();
    List<UUID> ids = List.of(product, second);
    Product p = new Product(product, UUID.randomUUID(), "Rice", "ચોખા", "", 6000, 5000, "kg", "1 kg", null, true, 5, false,
        true, true, product, 0, 0);
    when(repo.productIds(user)).thenReturn(ids);
    when(catalog.visible(ids)).thenReturn(List.of(p));
    assertThat(service.list(user)).containsExactly(p);
  }

  @Test
  void removeIsPassedThrough() {
    service.remove(user, product);
    verify(repo).remove(user, product);
  }
}
