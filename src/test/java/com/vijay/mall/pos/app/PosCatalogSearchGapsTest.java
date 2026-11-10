package com.vijay.mall.pos.app;

import com.vijay.mall.common.tax.GstRates;
import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.infra.PosCatalogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Counter search by barcode and item code, incremental refresh, and the extra display fields. */
class PosCatalogSearchGapsTest {
  private final UUID rice = UUID.randomUUID();
  private final UUID dal = UUID.randomUUID();
  private CatalogService catalog;
  private PosCatalogRepository repo;
  private PosCatalogService service;

  private static ProductData product(UUID id, String name, String unit, String pack) {
    return new ProductData(id, UUID.randomUUID(), name, name, "", 6000, 5900, unit, pack, null, true, false, null, null, true, true);
  }

  @BeforeEach
  void setUp() {
    catalog = mock(CatalogService.class);
    repo = mock(PosCatalogRepository.class);
    InventoryService inventory = mock(InventoryService.class);
    service = new PosCatalogService(catalog, repo, mock(ShopService.class), inventory, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    when(repo.forProducts(any())).thenAnswer(i -> {
      Collection<UUID> ids = i.getArgument(0);
      return ids.stream().collect(java.util.stream.Collectors.toMap(x -> x, x -> new ProductPos(x, SoldBy.UNIT, null, GstRates.of(5), "1006")));
    });
    when(inventory.stocks(any())).thenReturn(Map.of(rice, new InventoryRepository.Stock(rice, 40, 5), dal, new InventoryRepository.Stock(dal, 9, 5)));
    when(repo.barcodesOf(any())).thenReturn(List.of("8901"));
    when(catalog.posVariantNames(any())).thenReturn(Map.of(rice, "Premium"));
  }

  private static Page<ProductData> page(ProductData... p) { return new Page<>(List.of(p), 0, 20, p.length); }

  @Test void plainNameSearchStillUsesTheNameQueryOnly() {
    when(catalog.posSearch("ri", 0, 20)).thenReturn(page(product(rice, "Basmati Rice", "kg", "1 kg")));
    var r = service.search("ri", 0, 20);
    assertThat(r.items()).hasSize(1);
    verify(catalog, never()).posSearch(any(), any(), any(), anyInt(), anyInt());
  }

  @Test void aBarcodeTypedInTheSearchBoxFindsItsProductFirst() {
    when(repo.productByBarcode("8901234567890")).thenReturn(Optional.of(rice));
    when(catalog.posSearch(eq(" 8901234567890 "), eq(Set.of(rice)), isNull(), eq(0), eq(20)))
        .thenReturn(page(product(rice, "Basmati Rice", "kg", "1 kg")));
    var r = service.search(" 8901234567890 ", 0, 20);
    assertThat(r.items().get(0).productId()).isEqualTo(rice);
    verify(repo, never()).findByPlu(any());
  }

  @Test void anItemCodeIsMatchedWithoutLeadingZeros() {
    when(repo.productByBarcode("000101")).thenReturn(Optional.empty());
    when(repo.findByPlu("101")).thenReturn(Optional.of(new ProductPos(dal, SoldBy.WEIGHT, "101", GstRates.of(5), "")));
    when(catalog.posSearch(eq("000101"), eq(Set.of(dal)), isNull(), eq(0), eq(20))).thenReturn(page(product(dal, "Toor Dal", "kg", "")));
    assertThat(service.search("000101", 0, 20).items().get(0).productId()).isEqualTo(dal);
  }

  @Test void barcodeAndItemCodeOfTwoProductsBothComeBeforeNameMatches() {
    when(repo.productByBarcode("1234")).thenReturn(Optional.of(rice));
    when(repo.findByPlu("1234")).thenReturn(Optional.of(new ProductPos(dal, SoldBy.UNIT, "1234", GstRates.of(5), "")));
    when(catalog.posSearch(eq("1234"), eq(Set.of(rice, dal)), isNull(), eq(0), eq(20))).thenReturn(page(product(rice, "A", "pc", ""), product(dal, "B", "pc", "")));
    assertThat(service.search("1234", 0, 20).total()).isEqualTo(2);
  }

  @Test void noQueryAndBlankQueryHaveNoExactMatches() {
    when(catalog.posSearch(null, 0, 20)).thenReturn(page());
    when(catalog.posSearch("  ", 0, 20)).thenReturn(page());
    assertThat(service.search(null, 0, 20).items()).isEmpty();
    assertThat(service.search("  ", 0, 20).items()).isEmpty();
    verify(repo, never()).productByBarcode(any());
  }

  @Test void updatedSinceAsksForProductsChangedInTheCounterTablesToo() {
    Instant since = Instant.parse("2026-10-01T00:00:00Z");
    when(catalog.posSearch(isNull(), eq(Set.of()), eq(since), eq(0), eq(20))).thenReturn(page(product(dal, "Toor Dal", "kg", "")));
    var r = service.search(null, 0, 20, since);
    assertThat(r.items()).extracting(p -> p.productId()).containsExactly(dal);
  }

  @Test void updatedSinceCombinesWithAnExactMatch() {
    Instant since = Instant.parse("2026-10-01T00:00:00Z");
    when(repo.productByBarcode("8901")).thenReturn(Optional.of(rice));
    when(catalog.posSearch(eq("8901"), eq(Set.of(rice)), eq(since), eq(0), eq(20))).thenReturn(page(product(rice, "Rice", "kg", "")));
    assertThat(service.search("8901", 0, 20, since).items()).hasSize(1);
  }

  @Test void productsCarryPackSizeUnitVariantAndDisplayName() {
    when(catalog.posSearch("rice", 0, 20)).thenReturn(page(product(rice, "Basmati Rice", "kg", "1 kg")));
    var p = service.search("rice", 0, 20).items().get(0);
    assertThat(p.packSize()).isEqualTo("1 kg");
    assertThat(p.unit()).isEqualTo("kg");
    assertThat(p.variantName()).isEqualTo("Premium");
    assertThat(p.displayName()).isEqualTo("Basmati Rice Premium 1 kg");
  }

  @Test void missingVariantPackAndUnitBecomeEmptyText() {
    when(catalog.posSearch("dal", 0, 20)).thenReturn(page(product(dal, "Toor Dal", null, null)));
    var p = service.search("dal", 0, 20).items().get(0);
    assertThat(p.packSize()).isEmpty();
    assertThat(p.unit()).isEmpty();
    assertThat(p.variantName()).isEmpty();
    assertThat(p.displayName()).isEqualTo("Toor Dal");
  }

  @Test void scanReturnsTheSameExtraFields() {
    when(catalog.posProducts(any())).thenReturn(List.of(product(rice, "Basmati Rice", "kg", "1 kg")));
    when(repo.productByBarcode("8901234567890")).thenReturn(Optional.of(rice));
    var shop = mock(ShopService.class);
    when(shop.get()).thenReturn(PosTestData.shop());
    var inv = mock(InventoryService.class);
    when(inv.stocks(any())).thenReturn(Map.of(rice, new InventoryRepository.Stock(rice, 40, 5)));
    var withStock = new PosCatalogService(catalog, repo, shop, inv, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(withStock.scan("8901234567890").product().displayName()).isEqualTo("Basmati Rice Premium 1 kg");
  }
}
