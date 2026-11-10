package com.vijay.mall.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.app.ImageStore;
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.AdminProductRow;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.tax.GstRates;
import com.vijay.mall.common.tax.ProductGst;
import com.vijay.mall.pos.app.PosCatalogService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The admin product list reads the GST of the whole page with one lookup and defaults the products nobody configured. */
class AdminProductGstTest {
  private final CatalogService catalog = mock(CatalogService.class);
  private final PosCatalogService pos = mock(PosCatalogService.class);
  private final AdminCatalogController controller = new AdminCatalogController(catalog, mock(ImageStore.class), pos);

  private static AdminProduct product(UUID id) {
    return new AdminProduct(id, UUID.randomUUID(), "n", "g", "", 200, 100, "pc", "", null, true, 5, false, true, true, 5, 1, true,
        (Instant) null, null, null, 0, 0);
  }

  @Test
  void aPageOfProductsIsEnrichedWithOneBatchedLookup() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    when(catalog.adminList(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.anyInt())).thenReturn(new Page<>(List.of(product(a), product(b), product(c)), 0, 20, 3));
    when(pos.gstFor(anyCollection())).thenReturn(Map.of(a, new ProductGst(GstRates.of("1.5"), "1006", true), b, ProductGst.UNSET));

    Page<AdminProductRow> page = controller.list(null, null, null, null, false, null, 0, 20);

    verify(pos, times(1)).gstFor(List.of(a, b, c));
    assertThat(page.items()).extracting(r -> r.product().id()).containsExactly(a, b, c);
    assertThat(page.items().get(0).gstPercent()).isEqualByComparingTo("1.5");
    assertThat(page.items().get(0).hsn()).isEqualTo("1006");
    assertThat(page.items().get(0).gstConfigured()).isTrue();
    assertThat(page.items().get(1).gstConfigured()).isFalse();
    // a product missing from the lookup is the default as well
    assertThat(page.items().get(2).gstPercent()).isEqualByComparingTo("0");
    assertThat(page.items().get(2).hsn()).isEmpty();
    assertThat(page.items().get(2).gstConfigured()).isFalse();
  }

  @Test
  void theDetailCarriesTheSavedRate() {
    UUID a = UUID.randomUUID();
    when(catalog.adminGet(a)).thenReturn(product(a));
    when(pos.gstFor(List.of(a))).thenReturn(Map.of(a, new ProductGst(GstRates.of(18), "2106", true)));
    AdminProductRow row = controller.get(a);
    assertThat(row.gstPercent()).isEqualByComparingTo("18");
    assertThat(row.hsn()).isEqualTo("2106");
    assertThat(row.product().id()).isEqualTo(a);
    when(pos.gstFor(List.of(a))).thenReturn(Map.of());
    assertThat(controller.get(a).gstConfigured()).isFalse();
  }

  @Test
  void theProductFieldsStayAtTheTopLevelOfTheJsonAndTheRateIsAPlainNumber() throws Exception {
    UUID a = UUID.randomUUID();
    String json = JsonMapper.builder().build().writeValueAsString(AdminProductRow.of(product(a), new ProductGst(GstRates.of("1.50"), "1006", true)));
    assertThat(json).contains("\"id\":\"" + a + "\"").contains("\"pricePaise\":100").contains("\"gstPercent\":1.5,").contains("\"hsn\":\"1006\"")
        .contains("\"gstConfigured\":true").doesNotContain("\"product\"");
  }
}
