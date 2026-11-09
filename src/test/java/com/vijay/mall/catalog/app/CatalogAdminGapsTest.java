package com.vijay.mall.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.AdminCategory;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.catalog.infra.BannerRepository;
import com.vijay.mall.catalog.infra.CategoryRepository;
import com.vijay.mall.catalog.infra.ProductRepository;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.ReferenceNumbers;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository.Stock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for the admin catalog additions: image scopes, product delete, partial update, category admin and hot-sale paging. */
class CatalogAdminGapsTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private final UUID actor = UUID.randomUUID();
  private final UUID cat = UUID.randomUUID();
  private final UUID group = UUID.randomUUID();
  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID c = UUID.randomUUID();

  private ProductRepository products;
  private CategoryRepository categories;
  private AuditService audit;
  private LiveEvents live;
  private CatalogService service;

  @BeforeEach
  void setUp() {
    products = mock(ProductRepository.class);
    categories = mock(CategoryRepository.class);
    audit = mock(AuditService.class);
    live = mock(LiveEvents.class);
    InventoryService inventory = mock(InventoryService.class);
    service = new CatalogService(products, categories, mock(BannerRepository.class), inventory, audit,
        Clock.fixed(NOW, ZoneOffset.UTC), mock(ReferenceNumbers.class));
    service.setLive(live);
    when(inventory.stocks(any())).thenAnswer(inv -> {
      Map<UUID, Stock> out = new HashMap<>();
      for (UUID id : (Collection<UUID>) inv.getArgument(0)) out.put(id, new Stock(id, 7, 5));
      return out;
    });
  }

  private ProductData product(UUID id, String image, boolean active) {
    return new ProductData(id, cat, "Rice", "ચોખા", "d", 6000, 5000, "kg", "1 kg", image, active, false, null, null, true, false,
        group, 0, 0);
  }

  private void members(ProductData... rows) {
    when(products.allMembersOf(any())).thenReturn(List.of(rows));
    for (ProductData r : rows) when(products.find(r.id())).thenReturn(Optional.of(r));
  }

  // ---------- image scopes ----------
  @Test
  void imageScopeParsesValuesAndRejectsJunk() {
    assertThat(CatalogService.ImageScope.parse(null)).isEqualTo(CatalogService.ImageScope.ONE);
    assertThat(CatalogService.ImageScope.parse("  ")).isEqualTo(CatalogService.ImageScope.ONE);
    assertThat(CatalogService.ImageScope.parse("Missing")).isEqualTo(CatalogService.ImageScope.MISSING);
    assertThat(CatalogService.ImageScope.parse(" all ")).isEqualTo(CatalogService.ImageScope.ALL);
    assertThatThrownBy(() -> CatalogService.ImageScope.parse("some")).isInstanceOf(ApiException.class);
  }

  @Test
  void oneScopeTouchesOnlyTheAddressedPack() {
    members(product(a, null, true), product(b, null, true));
    service.setImage(a, "/img/new.jpg", CatalogService.ImageScope.ONE);
    verify(products).setImage(a, "/img/new.jpg", NOW);
    verify(products, never()).setImage(b, "/img/new.jpg", NOW);
  }

  @Test
  void missingScopeFillsPacksWithoutAPictureAndTheAddressedOne() {
    members(product(a, "/img/old.jpg", true), product(b, null, true), product(c, " ", true));
    service.setImage(a, "/img/new.jpg", CatalogService.ImageScope.MISSING);
    verify(products).setImage(a, "/img/new.jpg", NOW);
    verify(products).setImage(b, "/img/new.jpg", NOW);
    verify(products).setImage(c, "/img/new.jpg", NOW);
    verify(live).catalog();
  }

  @Test
  void missingScopeLeavesSiblingsThatHaveOne() {
    members(product(a, null, true), product(b, "/img/keep.jpg", true));
    service.setImage(a, "/img/new.jpg", CatalogService.ImageScope.MISSING);
    verify(products).setImage(a, "/img/new.jpg", NOW);
    verify(products, never()).setImage(b, "/img/new.jpg", NOW);
  }

  @Test
  void allScopeOverwritesEveryPack() {
    members(product(a, "/img/1.jpg", true), product(b, "/img/2.jpg", true));
    service.setImage(a, "/img/new.jpg", CatalogService.ImageScope.ALL);
    verify(products).setImage(a, "/img/new.jpg", NOW);
    verify(products).setImage(b, "/img/new.jpg", NOW);
  }

  // ---------- customer variants fall back to the group picture ----------
  @Test
  void aVariantWithoutAPictureShowsTheGroupPicture() {
    ProductData with = product(a, "/img/a.jpg", true);
    ProductData without = product(b, null, true);
    when(products.allMembersOf(group)).thenReturn(List.of(with, without));
    when(products.isPosOnly(any())).thenReturn(false);
    when(products.variantInfo(any())).thenReturn(Map.of());
    CatalogService.ProductGroup g = service.group(group);
    assertThat(g.imageUrl()).isEqualTo("/img/a.jpg");
    assertThat(g.variants()).extracting(CatalogService.Variant::imageUrl).containsOnly("/img/a.jpg");
  }

  @Test
  void whenNoPackHasAPictureTheyStayNull() {
    when(products.allMembersOf(group)).thenReturn(List.of(product(a, null, true), product(b, null, true)));
    when(products.variantInfo(any())).thenReturn(Map.of());
    CatalogService.ProductGroup g = service.group(group);
    assertThat(g.imageUrl()).isNull();
    assertThat(g.variants()).extracting(CatalogService.Variant::imageUrl).containsOnlyNulls();
  }

  // ---------- product delete ----------
  @Test
  void neverUsedProductIsRemovedForGood() {
    members(product(a, null, true));
    when(products.usedBy(a)).thenReturn(null);
    CatalogService.DeleteResult r = service.deleteProduct(a, actor);
    assertThat(r.deleted()).isTrue();
    assertThat(r.deactivated()).isFalse();
    verify(products).purge(a);
    verify(audit).log(actor, "PRODUCT_DELETE", "product", a, "Rice");
    verify(live).catalog();
  }

  @Test
  void usedActiveProductIsSwitchedOff() {
    members(product(a, null, true));
    when(products.usedBy(a)).thenReturn("orders");
    CatalogService.DeleteResult r = service.deleteProduct(a, actor);
    assertThat(r.deleted()).isFalse();
    assertThat(r.deactivated()).isTrue();
    assertThat(r.reason()).contains("orders");
    verify(products).setActive(a, false, NOW);
    verify(products, never()).purge(any());
    verify(audit).log(actor, "PRODUCT_DEACTIVATE", "product", a, "Rice");
    verify(live).catalog();
  }

  @Test
  void usedInactiveProductStaysDeactivatedWithoutNewWrites() {
    members(product(a, null, false));
    when(products.usedBy(a)).thenReturn("reviews");
    CatalogService.DeleteResult r = service.deleteProduct(a, actor);
    assertThat(r.deactivated()).isTrue();
    verify(products, never()).setActive(any(), anyBoolean(), any());
    verify(audit, never()).log(any(), any(), any(), any(), any());
  }

  @Test
  void deletingAnUnknownProductIs404() {
    when(products.find(a)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.deleteProduct(a, actor)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.status().value()).isEqualTo(404));
  }

  // ---------- partial update ----------
  @Test
  void patchChangesOnlyTheGivenFieldsAndKeepsStock() {
    ProductData d = new ProductData(a, cat, "Rice", "ચોખા", "d", 6000, 5000, "kg", "1 kg", null, true, true,
        NOW.minusSeconds(60), NOW.plusSeconds(60), true, true, group, 0, 0);
    members(d);
    when(products.variantInfo(any())).thenReturn(Map.of());
    service.patch(a, new ProductPatch(Map.of("pricePaise", 5500)), actor);
    verify(products).update(a, cat, "Rice", "ચોખા", "d", 6000L, 5500L, "kg", "1 kg", true, true, NOW.minusSeconds(60),
        NOW.plusSeconds(60), true, true, NOW);
  }

  @Test
  void patchWithVariantKeysAlsoSetsTheVariant() {
    members(product(a, null, true));
    when(products.variantInfo(any())).thenReturn(Map.of(a, new ProductRepository.VariantInfo("Old", 2)));
    service.patch(a, new ProductPatch(Map.of("variantName", " Mango ", "variantOrder", 4)), actor);
    verify(products).setVariant(a, "Mango", 4);
  }

  @Test
  void patchRejectsAnMrpBelowTheMergedPrice() {
    members(product(a, null, true));
    assertThatThrownBy(() -> service.patch(a, new ProductPatch(Map.of("pricePaise", 9000)), actor))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));
  }

  // ---------- categories ----------
  @Test
  void adminCategoriesListEverything() {
    AdminCategory hidden = new AdminCategory(cat, "Old", "જૂનું", null, 3, false);
    when(categories.listAll()).thenReturn(List.of(hidden));
    assertThat(service.adminCategories()).containsExactly(hidden);
  }

  @Test
  void categoryDeleteChecksUseAndExistence() {
    when(categories.find(cat)).thenReturn(Optional.of(new Category(cat, "Old", "જૂનું", null, 3)));
    when(categories.inUse(cat)).thenReturn(true);
    assertThatThrownBy(() -> service.deleteCategory(cat)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status().value()).isEqualTo(409);
      assertThat(e.code()).isEqualTo("CATEGORY_IN_USE");
    });
    verify(categories, never()).delete(any());

    when(categories.inUse(cat)).thenReturn(false);
    service.deleteCategory(cat);
    verify(categories).delete(cat);
    verify(live).catalog();

    UUID other = UUID.randomUUID();
    when(categories.find(other)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.deleteCategory(other)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.status().value()).isEqualTo(404));
  }

  @Test
  void categoryImageIsStoredAndUnknownIs404() {
    AdminCategory after = new AdminCategory(cat, "Old", "જૂનું", "/img/c.jpg", 3, true);
    when(categories.find(cat)).thenReturn(Optional.of(new Category(cat, "Old", "જૂનું", null, 3)));
    when(categories.findAdmin(cat)).thenReturn(Optional.of(after));
    assertThat(service.setCategoryImage(cat, "/img/c.jpg")).isEqualTo(after);
    verify(categories).setImage(cat, "/img/c.jpg");

    UUID other = UUID.randomUUID();
    when(categories.find(other)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.setCategoryImage(other, "/x")).isInstanceOf(ApiException.class);
  }

  // ---------- hot sale paging ----------
  @ParameterizedTest
  @ValueSource(ints = {-3, 0, 1, 20, 50, 51, 999})
  void hotSaleClampsSizeAndPage(int size) {
    int expected = Math.max(1, Math.min(size, 50));
    when(products.searchHotInStock(any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 0, expected, 0));
    CatalogService.HotSale hs = service.hotSale(-2, size);
    verify(products).searchHotInStock(NOW, 0, expected);
    assertThat(hs.size()).isEqualTo(expected);
  }

  @Test
  void hotSaleReportsPagingOfTheProductsPart() {
    ProductData d = product(a, null, true);
    when(products.searchHotInStock(NOW, 1, 1)).thenReturn(new Page<>(List.of(d), 1, 1, 5));
    CatalogService.HotSale hs = service.hotSale(1, 1);
    assertThat(hs.page()).isEqualTo(1);
    assertThat(hs.total()).isEqualTo(5);
    assertThat(hs.products()).hasSize(1);
  }

  @Test
  void hotSaleCompatConstructorDescribesOnePage() {
    CatalogService.HotSale hs = new CatalogService.HotSale(List.of(), List.of());
    assertThat(hs.page()).isZero();
    assertThat(hs.total()).isZero();
  }

  // ---------- B10-11: an omitted active keeps the current value ----------
  @Test
  void groupUpdateWithoutActiveKeepsTheCurrentValue() {
    when(products.allMembersOf(group)).thenReturn(List.of(product(a, null, false)));
    when(products.variantInfo(any())).thenReturn(Map.of());
    service.updateGroup(group, new CatalogService.GroupInput(cat, "Rice", "ચોખા", "d", null, null, null, null, null, null), actor);
    verify(products).updateShared(group, cat, "Rice", "ચોખા", "d", false, null, null, true, true, null, NOW);
  }
}
