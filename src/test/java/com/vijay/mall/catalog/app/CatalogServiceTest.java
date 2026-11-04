package com.vijay.mall.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.catalog.domain.HotBanner;
import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.catalog.infra.BannerRepository;
import com.vijay.mall.catalog.infra.CategoryRepository;
import com.vijay.mall.catalog.infra.ProductRepository;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private final UUID actor = UUID.randomUUID();
  private final UUID cat = UUID.randomUUID();
  private final UUID pid = UUID.randomUUID();

  private ProductRepository products;
  private CategoryRepository categories;
  private BannerRepository banners;
  private InventoryService inventory;
  private AuditService audit;
  private CatalogService service;
  private final Map<UUID, Stock> stock = new HashMap<>();

  @BeforeEach
  void setUp() {
    products = mock(ProductRepository.class);
    categories = mock(CategoryRepository.class);
    banners = mock(BannerRepository.class);
    inventory = mock(InventoryService.class);
    audit = mock(AuditService.class);
    service = new CatalogService(products, categories, banners, inventory, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    when(inventory.stocks(any())).thenAnswer(inv -> {
      Map<UUID, Stock> out = new HashMap<>();
      for (UUID id : (Collection<UUID>) inv.getArgument(0)) out.put(id, stock.getOrDefault(id, new Stock(id, 0, 5)));
      return out;
    });
  }

  private ProductData data(UUID id, boolean active, boolean hot, Instant hotStart, Instant hotEnd) {
    return new ProductData(id, cat, "Rice", "ચોખા", "d", 6000, 5000, "kg", "1 kg", "/img/x_400.jpg", active, hot, hotStart,
        hotEnd, true, false);
  }

  private ProductData data(UUID id) { return data(id, true, false, null, null); }

  private static void assertApi(Throwable t, int status, String code) {
    assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status().value()).isEqualTo(status);
      assertThat(e.code()).isEqualTo(code);
    });
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> fields(Throwable t) {
    return (Map<String, Object>) ((ApiException) t).details().get("fields");
  }

  // ---------- customer list ----------
  @ParameterizedTest
  @CsvSource({"-5,-5,0,1", "0,0,0,1", "3,1,3,1", "2,20,2,20", "0,50,0,50", "0,51,0,50", "0,5000,0,50"})
  void listClampsPageAndSizeBeforeSearching(int page, int size, int expectedPage, int expectedSize) {
    when(products.searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt()))
        .thenReturn(new Page<>(List.of(), expectedPage, expectedSize, 0));

    Page<Product> out = service.list("rice", cat, true, page, size);

    verify(products).searchInStock("rice", cat, true, NOW, expectedPage, expectedSize);
    assertThat(out.items()).isEmpty();
    assertThat(out.page()).isEqualTo(expectedPage);
    assertThat(out.size()).isEqualTo(expectedSize);
  }

  @Test
  void listShowsCustomerFieldsAndOnlyReportsHotSaleInsideItsWindow() {
    UUID live = UUID.randomUUID(), expired = UUID.randomUUID(), future = UUID.randomUUID(), flagOff = UUID.randomUUID();
    UUID startsNow = UUID.randomUUID(), endsNow = UUID.randomUUID(), empty = UUID.randomUUID(), plenty = UUID.randomUUID();
    when(products.searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(
        data(live, true, true, NOW.minusSeconds(60), NOW.plusSeconds(60)),
        data(expired, true, true, NOW.minusSeconds(60), NOW.minusSeconds(1)),
        data(future, true, true, NOW.plusSeconds(1), null),
        data(flagOff, true, false, null, null),
        data(startsNow, true, true, NOW, null),
        data(endsNow, true, true, null, NOW),
        data(empty), data(plenty)), 0, 20, 8));
    stock.put(live, new Stock(live, 7, 5));
    stock.put(plenty, new Stock(plenty, 250, 5));

    Page<Product> out = service.list(null, null, false, 0, 20);

    Map<UUID, Product> byId = new HashMap<>();
    out.items().forEach(p -> byId.put(p.id(), p));
    assertThat(out.total()).isEqualTo(8);
    assertThat(byId.get(live).hotSale()).isTrue();
    assertThat(byId.get(expired).hotSale()).isFalse();
    assertThat(byId.get(future).hotSale()).isFalse();
    assertThat(byId.get(flagOff).hotSale()).isFalse();
    assertThat(byId.get(startsNow).hotSale()).isTrue();   // window is inclusive at both ends
    assertThat(byId.get(endsNow).hotSale()).isTrue();
    assertThat(byId.get(live).inStock()).isTrue();
    assertThat(byId.get(live).qtyAvailable()).isEqualTo(7);
    assertThat(byId.get(empty).inStock()).isFalse();
    assertThat(byId.get(empty).qtyAvailable()).isZero();
    assertThat(byId.get(plenty).qtyAvailable()).isEqualTo(99); // never shows more than 99
    assertThat(byId.get(live).nameGu()).isEqualTo("ચોખા");
    assertThat(byId.get(live).pricePaise()).isEqualTo(5000);
    assertThat(byId.get(live).coinRedeemable()).isFalse();
  }

  @Test
  void getReturnsActiveProductAndHidesInactiveOnesAsNotFound() {
    UUID inactive = UUID.randomUUID(), unknown = UUID.randomUUID();
    when(products.find(pid)).thenReturn(Optional.of(data(pid)));
    when(products.find(inactive)).thenReturn(Optional.of(data(inactive, false, false, null, null)));
    when(products.find(unknown)).thenReturn(Optional.empty());
    stock.put(pid, new Stock(pid, 4, 5));

    Product p = service.get(pid);
    assertThat(p.id()).isEqualTo(pid);
    assertThat(p.qtyAvailable()).isEqualTo(4);
    assertThatThrownBy(() -> service.get(inactive)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
    assertThatThrownBy(() -> service.get(unknown)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
  }

  @Test
  void shopListHidesOutOfStockUnlessAskedToInclude() {
    when(products.searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 0, 20, 0));
    when(products.search(any(), any(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 0, 20, 0));
    service.list(null, null, false, 0, 20);
    verify(products).searchInStock(null, null, false, NOW, 0, 20);
    service.list(null, null, false, true, 0, 20);
    verify(products).search(null, null, false, true, NOW, 0, 20);
  }

  @Test
  void hotSaleReturnsLiveBannersAndFirstTwentyHotProducts() {
    HotBanner banner = new HotBanner(UUID.randomUUID(), "Diwali", "", null, null, null, true);
    when(banners.live(NOW)).thenReturn(List.of(banner));
    when(products.searchInStock(null, null, true, NOW, 0, 20))
        .thenReturn(new Page<>(List.of(data(pid, true, true, null, null)), 0, 20, 1));

    CatalogService.HotSale hs = service.hotSale();

    assertThat(hs.banners()).containsExactly(banner);
    assertThat(hs.products()).hasSize(1);
    assertThat(hs.products().get(0).hotSale()).isTrue();
  }

  @Test
  void categoriesAndNamesDelegate() {
    Category c = new Category(cat, "Dairy", "ડેરી", null, 1);
    when(categories.listActive()).thenReturn(List.of(c));
    when(products.names(List.of(pid))).thenReturn(Map.of(pid, "Rice"));
    assertThat(service.categories()).containsExactly(c);
    assertThat(service.names(List.of(pid))).containsEntry(pid, "Rice");
  }

  // ---------- pricing a cart ----------
  @Test
  void priceBuildsLinesFromCurrentCatalogMergingDuplicatesInFirstSeenOrder() {
    UUID other = UUID.randomUUID();
    when(products.findAll(any())).thenReturn(List.of(data(other), data(pid)));

    List<PricedLine> lines = service.price(List.of(new CatalogService.ItemRequest(pid, 2),
        new CatalogService.ItemRequest(other, 1), new CatalogService.ItemRequest(pid, 3)));

    assertThat(lines).extracting(PricedLine::productId).containsExactly(pid, other);
    PricedLine first = lines.get(0);
    assertThat(first.qty()).isEqualTo(5);
    assertThat(first.unitPricePaise()).isEqualTo(5000);
    assertThat(first.lineTotalPaise()).isEqualTo(25000);
    assertThat(first.categoryId()).isEqualTo(cat);
    assertThat(first.name()).isEqualTo("Rice");
    assertThat(first.coinRedeemable()).isFalse();
    assertThat(first.earnsCoins()).isTrue();
    verify(products).findAll(List.of(pid, other));
  }

  @Test
  void emptyOrMissingCartIsRejected() {
    assertThatThrownBy(() -> service.price(null)).satisfies(t -> {
      assertApi(t, 400, "VALIDATION_FAILED");
      assertThat(fields(t)).containsKey("items");
    });
    assertThatThrownBy(() -> service.price(List.of())).satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    verifyNoInteractions(products);
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 100, 1000})
  void quantityOutsideOneToNinetyNineIsRejected(int qty) {
    assertThatThrownBy(() -> service.price(List.of(new CatalogService.ItemRequest(pid, qty)))).satisfies(t -> {
      assertApi(t, 400, "VALIDATION_FAILED");
      assertThat(fields(t)).containsKey("qty");
    });
    verifyNoInteractions(products);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 50, 99})
  void quantityInsideTheRangeIsAccepted(int qty) {
    when(products.findAll(any())).thenReturn(List.of(data(pid)));
    assertThat(service.price(List.of(new CatalogService.ItemRequest(pid, qty))).get(0).lineTotalPaise()).isEqualTo(5000L * qty);
  }

  @Test
  void eachLineQuantityIsCheckedBeforeMerging() {
    // 60 + 60 would merge to 120, but each request line is checked on its own; merging itself is not capped
    when(products.findAll(any())).thenReturn(List.of(data(pid)));
    assertThat(service.price(List.of(new CatalogService.ItemRequest(pid, 60), new CatalogService.ItemRequest(pid, 60)))
        .get(0).qty()).isEqualTo(120);
  }

  @Test
  void unknownAndInactiveProductsAreRejectedByName() {
    UUID inactive = UUID.randomUUID(), unknown = UUID.randomUUID();
    when(products.findAll(any())).thenReturn(List.of(data(pid), data(inactive, false, false, null, null)));

    assertThatThrownBy(() -> service.price(List.of(new CatalogService.ItemRequest(pid, 1), new CatalogService.ItemRequest(unknown, 1))))
        .satisfies(t -> {
          assertApi(t, 400, "VALIDATION_FAILED");
          assertThat(fields(t).get("productId").toString()).contains(unknown.toString());
        });
    assertThatThrownBy(() -> service.price(List.of(new CatalogService.ItemRequest(inactive, 1))))
        .satisfies(t -> assertThat(fields(t).get("productId").toString()).contains(inactive.toString()));
  }

  // ---------- admin read ----------
  @Test
  void adminListSearchesAllProductsIncludingInactiveAndAddsStock() {
    UUID inactive = UUID.randomUUID();
    when(products.search(any(), any(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt()))
        .thenReturn(new Page<>(List.of(data(inactive, false, false, null, null)), 0, 50, 1));
    stock.put(inactive, new Stock(inactive, 2, 9));

    Page<AdminProduct> out = service.adminList("x", null, -1, 999);

    verify(products).search("x", null, false, false, NOW, 0, 50);
    AdminProduct p = out.items().get(0);
    assertThat(p.active()).isFalse();
    assertThat(p.qtyOnHand()).isEqualTo(2);
    assertThat(p.lowThreshold()).isEqualTo(9);
  }

  @Test
  void adminGetShowsRawStockAndUnknownIdIsNotFound() {
    when(products.find(pid)).thenReturn(Optional.of(data(pid)));
    stock.put(pid, new Stock(pid, 150, 7));
    AdminProduct p = service.adminGet(pid);
    assertThat(p.qtyOnHand()).isEqualTo(150);
    assertThat(p.qtyAvailable()).isEqualTo(99);
    assertThat(p.lowThreshold()).isEqualTo(7);
    UUID unknown = UUID.randomUUID();
    when(products.find(unknown)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.adminGet(unknown)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
  }

  // ---------- create ----------
  private CatalogService.ProductInput input(long mrp, long price, Instant hotStart, Instant hotEnd, int qty, int low) {
    return new CatalogService.ProductInput(cat, "Atta", "આટો", null, mrp, price, null, null, true, false, hotStart, hotEnd,
        true, true, qty, low);
  }

  @Test
  void createStoresProductWithDefaultsCreatesStockRowAndAudits() {
    UUID id = UUID.randomUUID();
    when(products.insert(any(), any(), any(), any(), anyLong(), anyLong(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
        anyBoolean(), anyBoolean(), any())).thenReturn(id);
    when(products.find(id)).thenReturn(Optional.of(data(id)));

    AdminProduct out = service.create(input(6000, 5000, null, null, 12, 0), actor);

    verify(products).insert(eq(cat), eq("Atta"), eq("આટો"), eq(""), eq(6000L), eq(5000L), eq("pc"), eq(""), eq(true),
        eq(false), isNull(), isNull(), eq(true), eq(true), eq(NOW));
    verify(inventory).create(id, 12, 5); // zero threshold falls back to 5
    verify(audit).log(actor, "CREATE", "product", id, "Atta @ 5000");
    assertThat(out.id()).isEqualTo(id);
  }

  @Test
  void createClampsNegativeStockToZeroAndKeepsPositiveThreshold() {
    UUID id = UUID.randomUUID();
    when(products.insert(any(), any(), any(), any(), anyLong(), anyLong(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
        anyBoolean(), anyBoolean(), any())).thenReturn(id);
    when(products.find(id)).thenReturn(Optional.of(data(id)));
    service.create(input(100, 100, null, null, -4, 8), actor);
    verify(inventory).create(id, 0, 8);
  }

  @Test
  void blankUnitFallsBackToPieceButARealUnitIsKept() {
    UUID id = UUID.randomUUID();
    when(products.insert(any(), any(), any(), any(), anyLong(), anyLong(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
        anyBoolean(), anyBoolean(), any())).thenReturn(id);
    when(products.find(id)).thenReturn(Optional.of(data(id)));
    service.create(new CatalogService.ProductInput(cat, "A", "A", "desc", 10, 10, "  ", "500 g", true, false, null, null,
        true, true, 1, 1), actor);
    verify(products).insert(any(), any(), any(), eq("desc"), anyLong(), anyLong(), eq("pc"), eq("500 g"), anyBoolean(),
        anyBoolean(), any(), any(), anyBoolean(), anyBoolean(), any());
    service.create(new CatalogService.ProductInput(cat, "A", "A", null, 10, 10, "kg", null, true, false, null, null,
        true, true, 1, 1), actor);
    verify(products).insert(any(), any(), any(), any(), anyLong(), anyLong(), eq("kg"), eq(""), anyBoolean(),
        anyBoolean(), any(), any(), anyBoolean(), anyBoolean(), any());
  }

  static Object[][] invalidInputs() {
    Instant t = NOW;
    return new Object[][] {
        {-1L, 0L, null, null, "pricePaise"},
        {5L, -1L, null, null, "pricePaise"},
        {-1L, -1L, null, null, "pricePaise"},
        {99L, 100L, null, null, "mrpPaise"},
        {100L, 100L, t, t.minusSeconds(1), "hotEnd"},
    };
  }

  @ParameterizedTest
  @MethodSource("invalidInputs")
  void createAndUpdateRejectInvalidInputBeforeTouchingAnything(long mrp, long price, Instant start, Instant end, String field) {
    var in = input(mrp, price, start, end, 1, 1);
    assertThatThrownBy(() -> service.create(in, actor)).satisfies(t -> {
      assertApi(t, 400, "VALIDATION_FAILED");
      assertThat(fields(t)).containsKey(field);
    });
    assertThatThrownBy(() -> service.update(pid, in, actor)).satisfies(t -> assertThat(fields(t)).containsKey(field));
    verifyNoInteractions(products, inventory, audit);
  }

  static Object[][] validEdgeInputs() {
    return new Object[][] {
        {0L, 0L, null, null},                       // free item
        {100L, 100L, null, null},                   // mrp equals price
        {100L, 100L, NOW, NOW},                     // zero-length window
        {100L, 100L, NOW, null},                    // open ended
        {100L, 100L, null, NOW},                    // open start
    };
  }

  @ParameterizedTest
  @MethodSource("validEdgeInputs")
  void boundaryInputsAreAccepted(long mrp, long price, Instant start, Instant end) {
    UUID id = UUID.randomUUID();
    when(products.insert(any(), any(), any(), any(), anyLong(), anyLong(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
        anyBoolean(), anyBoolean(), any())).thenReturn(id);
    when(products.find(id)).thenReturn(Optional.of(data(id)));
    assertThat(service.create(input(mrp, price, start, end, 1, 1), actor).id()).isEqualTo(id);
  }

  // ---------- update ----------
  @Test
  void updateAdjustsStockByTheDifferenceAndAuditsThePriceChange() {
    when(products.find(pid)).thenReturn(Optional.of(data(pid)));
    stock.put(pid, new Stock(pid, 10, 5));

    service.update(pid, input(7000, 6500, null, null, 4, 3), actor);

    verify(products).update(eq(pid), eq(cat), eq("Atta"), eq("આટો"), eq(""), eq(7000L), eq(6500L), eq("pc"), eq(""), eq(true),
        eq(false), isNull(), isNull(), eq(true), eq(true), eq(NOW));
    verify(inventory).adjust(pid, -6, "ADJUST", actor);
    verify(inventory).setLowThreshold(pid, 3);
    verify(audit).log(actor, "UPDATE", "product", pid, "price 5000 -> 6500");
  }

  @Test
  void updateWithUnchangedStockAndNoThresholdLeavesInventoryAlone() {
    when(products.find(pid)).thenReturn(Optional.of(data(pid)));
    stock.put(pid, new Stock(pid, 10, 5));
    service.update(pid, input(100, 100, null, null, 10, 0), actor);
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any());
    verify(inventory, never()).setLowThreshold(any(), anyInt());
  }

  @Test
  void updateIgnoresANegativeStockFigureAndRaisesStockWhenHigher() {
    when(products.find(pid)).thenReturn(Optional.of(data(pid)));
    stock.put(pid, new Stock(pid, 10, 5));
    service.update(pid, input(100, 100, null, null, -1, 1), actor);
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any());
    service.update(pid, input(100, 100, null, null, 25, 1), actor);
    verify(inventory).adjust(pid, 15, "ADJUST", actor);
  }

  @Test
  void updateOfUnknownProductIsNotFoundAndWritesNothing() {
    when(products.find(pid)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(pid, input(100, 100, null, null, 1, 1), actor))
        .satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
    verify(products, never()).update(any(), any(), any(), any(), any(), anyLong(), anyLong(), any(), any(), anyBoolean(),
        anyBoolean(), any(), any(), anyBoolean(), anyBoolean(), any());
    verifyNoInteractions(audit);
  }

  // ---------- restock / image ----------
  @ParameterizedTest
  @CsvSource(value = {"ADJUST,ADJUST", "RESTOCK,RESTOCK", "NULL,RESTOCK", "adjust,RESTOCK", "other,RESTOCK"}, nullValues = "NULL")
  void restockRecordsAdjustOnlyForTheExactReason(String reason, String recorded) {
    when(products.find(pid)).thenReturn(Optional.of(data(pid)));
    service.restock(pid, 5, reason, actor);
    verify(inventory).adjust(pid, 5, recorded, actor);
    verify(audit).log(actor, "STOCK", "product", pid, "delta 5");
  }

  @Test
  void restockAndSetImageOfUnknownProductAreNotFound() {
    when(products.find(pid)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.restock(pid, 5, null, actor)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
    assertThatThrownBy(() -> service.setImage(pid, "/img/x")).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
    verifyNoInteractions(inventory, audit);
    verify(products, never()).setImage(any(), any(), any());
  }

  @Test
  void setImageStoresTheUrlWithTheCurrentTime() {
    when(products.find(pid)).thenReturn(Optional.of(data(pid)));
    service.setImage(pid, "/img/products/a_400.jpg");
    verify(products).setImage(pid, "/img/products/a_400.jpg", NOW);
  }

  // ---------- categories ----------
  @Test
  void categoryCreateAndUpdate() {
    Category c = new Category(cat, "Dairy", "ડેરી", null, 2);
    when(categories.insert("Dairy", "ડેરી", 2, true)).thenReturn(cat);
    when(categories.find(cat)).thenReturn(Optional.of(c));
    assertThat(service.createCategory("Dairy", "ડેરી", 2, true)).isEqualTo(c);
    assertThat(service.updateCategory(cat, "Dairy", "ડેરી", 2, false)).isEqualTo(c);
    verify(categories).update(cat, "Dairy", "ડેરી", 2, false);
  }

  @Test
  void updatingAnUnknownCategoryIsNotFound() {
    when(categories.find(cat)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.updateCategory(cat, "a", "b", 0, true)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
    verify(categories, never()).update(any(), any(), any(), anyInt(), anyBoolean());
  }

  // ---------- banners ----------
  @Test
  void bannerCrudTurnsNullSubtitleIntoEmptyText() {
    UUID id = UUID.randomUUID();
    HotBanner b = new HotBanner(id, "Diwali", "", null, null, null, true);
    when(banners.insert("Diwali", "", null, null, null, true)).thenReturn(id);
    when(banners.find(id)).thenReturn(Optional.of(b));
    assertThat(service.createBanner("Diwali", null, null, null, null, true)).isEqualTo(b);
    assertThat(service.updateBanner(id, "Diwali", null, "/img/b.jpg", NOW, NOW.plusSeconds(5), false)).isEqualTo(b);
    verify(banners).update(id, "Diwali", "", "/img/b.jpg", NOW, NOW.plusSeconds(5), false);
    service.deleteBanner(id);
    verify(banners).delete(id);
    when(banners.all()).thenReturn(List.of(b));
    assertThat(service.adminBanners()).containsExactly(b);
  }

  @Test
  void updatingAnUnknownBannerIsNotFound() {
    UUID id = UUID.randomUUID();
    when(banners.find(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.updateBanner(id, "t", "s", null, null, null, true)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
    verify(banners, never()).update(any(), any(), any(), any(), any(), any(), anyBoolean());
  }

  // ---------- variants, ratings, saved-items helper, stock history ----------
  private void stubInsert(UUID id) {
    when(products.insert(any(), any(), any(), any(), anyLong(), anyLong(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
        anyBoolean(), anyBoolean(), any())).thenReturn(id);
    when(products.find(id)).thenReturn(Optional.of(data(id)));
  }

  @Test
  void createPutsTheProductInAGroupWhenOneIsGiven() {
    UUID id = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    stubInsert(id);
    service.create(new CatalogService.ProductInput(cat, "Atta", "આટો", null, 6000, 5000, null, null, true, false, null, null,
        true, true, 0, 5, group), actor);
    verify(products).setGroup(id, group);
  }

  @Test
  void createWithoutAGroupLeavesTheProductAsItsOwnGroup() {
    UUID id = UUID.randomUUID();
    stubInsert(id);
    service.create(input(6000, 5000, null, null, 0, 5), actor);
    verify(products, never()).setGroup(any(), any());
  }

  @Test
  void updateCanMoveAProductToAnotherGroupAndOtherwiseKeepsIt() {
    UUID group = UUID.randomUUID();
    when(products.find(pid)).thenReturn(Optional.of(data(pid)));
    service.update(pid, new CatalogService.ProductInput(cat, "Atta", "આટો", null, 6000, 5000, null, null, true, false, null,
        null, true, true, 0, 5, group), actor);
    verify(products).setGroup(pid, group);

    service.update(pid, input(6000, 5000, null, null, 0, 5), actor);
    verify(products, org.mockito.Mockito.times(1)).setGroup(any(), any());
  }

  @Test
  void productsWithoutAGroupAreTheirOwnGroupAndHaveNoReviews() {
    ProductData d = data(pid);
    assertThat(d.productGroupId()).isEqualTo(pid);
    assertThat(d.rating()).isZero();
    assertThat(d.reviewCount()).isZero();
  }

  @Test
  void unitLabelIsThePackSizeOrTheUnitWhenThereIsNone() {
    assertThat(data(pid).unitLabel()).isEqualTo("1 kg");
    assertThat(new ProductData(pid, cat, "R", "R", "", 1, 1, "kg", "", null, true, false, null, null, true, true).unitLabel()).isEqualTo("kg");
    assertThat(new ProductData(pid, cat, "R", "R", "", 1, 1, "pc", null, null, true, false, null, null, true, true).unitLabel()).isEqualTo("pc");
  }

  @Test
  void pricedLinesCarryTheGujaratiNameAndPackLabel() {
    when(products.findAll(any())).thenReturn(List.of(data(pid)));
    PricedLine line = service.price(List.of(new CatalogService.ItemRequest(pid, 1))).get(0);
    assertThat(line.nameGu()).isEqualTo("ચોખા");
    assertThat(line.unitLabel()).isEqualTo("1 kg");
  }

  @Test
  void visibleKeepsTheGivenOrderAndLeavesOutMissingInactiveAndCounterOnlyProducts() {
    UUID y = UUID.randomUUID();
    UUID inactive = UUID.randomUUID();
    UUID counterOnly = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    List<UUID> ids = List.of(counterOnly, y, missing, inactive, pid);
    when(products.findAll(ids)).thenReturn(List.of(data(pid), data(y), data(inactive, false, false, null, null), data(counterOnly)));
    when(products.posOnlyAmong(ids)).thenReturn(java.util.Set.of(counterOnly));
    stock.put(pid, new Stock(pid, 3, 5));

    var out = service.visible(ids);

    assertThat(out).extracting(com.vijay.mall.catalog.domain.Product::id).containsExactly(y, pid);
    assertThat(out.get(1).inStock()).isTrue();
    assertThat(out.get(0).inStock()).isFalse();
  }

  @Test
  void visibleWithNothingToLookUpTouchesNothing() {
    assertThat(service.visible(List.of())).isEmpty();
    verifyNoInteractions(products);
  }

  @Test
  void updateRatingIsStoredOnTheProduct() {
    service.updateRating(pid, 4.5, 12);
    verify(products).setRating(pid, 4.5, 12);
  }

  @Test
  void stockMovementsShowProductNameAndPackLabel() {
    UUID gone = UUID.randomUUID();
    var moves = List.of(
        new com.vijay.mall.inventory.infra.InventoryRepository.Movement(UUID.randomUUID(), pid, -2, "ORDER", null, null, NOW),
        new com.vijay.mall.inventory.infra.InventoryRepository.Movement(UUID.randomUUID(), gone, 5, "RESTOCK", null, actor, NOW));
    when(inventory.movements(null, 0, 20)).thenReturn(new Page<>(moves, 0, 20, 2));
    when(products.findAll(any())).thenReturn(List.of(data(pid)));

    var out = service.stockMovements(null, 0, 20);

    assertThat(out.total()).isEqualTo(2);
    assertThat(out.items().get(0).name()).isEqualTo("Rice");
    assertThat(out.items().get(0).packLabel()).isEqualTo("1 kg");
    assertThat(out.items().get(0).delta()).isEqualTo(-2);
    assertThat(out.items().get(0).reason()).isEqualTo("ORDER");
    assertThat(out.items().get(1).name()).isEqualTo("?");
    assertThat(out.items().get(1).packLabel()).isEmpty();
    assertThat(out.items().get(1).cashierId()).isEqualTo(actor);
  }
}
