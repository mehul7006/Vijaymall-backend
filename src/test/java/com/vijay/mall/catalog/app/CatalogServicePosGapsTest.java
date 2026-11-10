package com.vijay.mall.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.catalog.infra.BannerRepository;
import com.vijay.mall.catalog.infra.CategoryRepository;
import com.vijay.mall.catalog.infra.ProductRepository;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.ReferenceNumbers;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.inventory.app.InventoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The counter helpers the POS module uses: exact-match search with a changed-since filter, and variant names. */
class CatalogServicePosGapsTest {
  private final ProductRepository products = mock(ProductRepository.class);
  private final CategoryRepository categories = mock(CategoryRepository.class);
  private final CatalogService service = new CatalogService(products, categories, mock(BannerRepository.class),
      mock(InventoryService.class), mock(AuditService.class), Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC),
      mock(ReferenceNumbers.class));

  @Test void posSearchPassesTheExactMatchesAndTheChangedFilterWithPagingClamped() {
    UUID id = UUID.randomUUID();
    Instant since = Instant.parse("2026-10-01T00:00:00Z");
    Page<ProductData> page = new Page<>(List.of(), 0, 20, 0);
    when(products.searchForCounter("q", Set.of(id), since, 0, 20)).thenReturn(page);
    assertThat(service.posSearch("q", Set.of(id), since, -3, 20)).isSameAs(page);
    verify(products).searchForCounter("q", Set.of(id), since, 0, 20);
  }

  @Test void variantNamesComeFromTheVariantInfo() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(products.variantInfo(Set.of(a, b))).thenReturn(Map.of(a, new ProductRepository.VariantInfo("Premium", 1)));
    assertThat(service.posVariantNames(Set.of(a, b))).containsEntry(a, "Premium").doesNotContainKey(b);
  }

  @Test void categoryNameIsTheEnglishNameWhetherTheCategoryIsActiveOrNotAndEmptyForNullOrUnknown() {
    UUID id = UUID.randomUUID();
    when(categories.find(id)).thenReturn(Optional.of(new Category(id, "Staples", "અનાજ", null, 1)));
    when(categories.find(UUID.fromString("00000000-0000-0000-0000-000000000001"))).thenReturn(Optional.empty());
    assertThat(service.categoryName(id)).contains("Staples");
    assertThat(service.categoryName(UUID.fromString("00000000-0000-0000-0000-000000000001"))).isEmpty();
    assertThat(service.categoryName(null)).isEmpty();
  }
}
