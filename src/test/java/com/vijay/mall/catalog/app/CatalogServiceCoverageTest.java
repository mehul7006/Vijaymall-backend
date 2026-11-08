package com.vijay.mall.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.catalog.infra.BannerRepository;
import com.vijay.mall.catalog.infra.CategoryRepository;
import com.vijay.mall.catalog.infra.ProductRepository;
import com.vijay.mall.catalog.infra.ProductRepository.VariantInfo;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.ReferenceNumbers;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository.Movement;
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

class CatalogServiceCoverageTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant PAST = NOW.minusSeconds(3600);
  private static final Instant FUTURE = NOW.plusSeconds(3600);
  private final UUID actor = UUID.randomUUID();
  private final UUID cat = UUID.randomUUID();

  private ProductRepository products;
  private InventoryService inventory;
  private AuditService audit;
  private ReferenceNumbers references;
  private CatalogService service;
  private final Map<UUID, Stock> stock = new HashMap<>();
  private final Map<UUID, ProductData> db = new HashMap<>();

  @BeforeEach
  void setUp() {
    products = mock(ProductRepository.class);
    inventory = mock(InventoryService.class);
    audit = mock(AuditService.class);
    references = mock(ReferenceNumbers.class);
    service = new CatalogService(products, mock(CategoryRepository.class), mock(BannerRepository.class), inventory, audit,
        Clock.fixed(NOW, ZoneOffset.UTC), references);
    when(inventory.stocks(any())).thenAnswer(inv -> {
      Map<UUID, Stock> out = new HashMap<>();
      for (UUID id : (Collection<UUID>) inv.getArgument(0)) out.put(id, stock.getOrDefault(id, new Stock(id, 0, 5)));
      return out;
    });
    when(products.find(any())).thenAnswer(inv -> Optional.ofNullable(db.get((UUID) inv.getArgument(0))));
  }

  private ProductData pd(UUID id, UUID group, long price, String pack, String image, boolean active, boolean hot,
                         Instant hs, Instant he, double rating, int reviews) {
    var d = new ProductData(id, cat, "Mango", "કેરી", "desc", price + 100, price, "kg", pack, image, active, hot, hs, he,
        true, false, group, rating, reviews);
    db.put(id, d);
    return d;
  }

  private ProductData simple(UUID id, UUID group) { return pd(id, group, 100, "1 kg", "/i.jpg", true, false, null, null, 0, 0); }

  private static void assertApi(Throwable t, int status, String code) {
    assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status().value()).isEqualTo(status);
      assertThat(e.code()).isEqualTo(code);
    });
  }

  // ---------- group / groups ----------

  @Test
  void aGroupWithManyVariantsPicksTheFirstInStockOneAndMergesRatingsAndImage() {
    UUID g = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    var big = pd(a, g, 200, "500 g", null, true, true, null, null, 4, 2);
    var small = pd(b, g, 100, null, "/b.jpg", true, false, null, null, 5, 2);
    stock.put(a, new Stock(a, 150, 5));
    stock.put(b, new Stock(b, 0, 5));
    when(products.allMembersOf(g)).thenReturn(List.of(big, small));
    when(products.variantInfo(any())).thenReturn(Map.of(a, new VariantInfo("Large", 2), b, new VariantInfo("Small", 1)));

    var out = service.group(g);

    assertThat(out.variants()).extracting(CatalogService.Variant::id).containsExactly(b, a);
    assertThat(out.variants().get(0).packSize()).isEmpty();
    assertThat(out.variants().get(0).variantName()).isEqualTo("Small");
    assertThat(out.variants().get(0).inStock()).isFalse();
    assertThat(out.variants().get(1).qtyAvailable()).isEqualTo(99);
    assertThat(out.defaultVariantId()).isEqualTo(a);
    assertThat(out.imageUrl()).isEqualTo("/b.jpg");
    assertThat(out.hotSale()).isTrue();
    assertThat(out.rating()).isEqualTo(4.5);
    assertThat(out.reviewCount()).isEqualTo(4);
    assertThat(out.fromPricePaise()).isEqualTo(100);
    assertThat(out.inStock()).isTrue();
    assertThat(service.variantIds(g)).containsExactly(b, a);
  }

  @Test
  void aGroupWithNothingInStockDefaultsToTheFirstVariantHasNoImageAndNoRating() {
    UUID g = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    var x = pd(a, g, 300, "1 kg", null, true, false, null, null, 0, 0);
    var y = pd(b, g, 100, "250 g", null, true, false, null, null, 0, 0);
    when(products.allMembersOf(g)).thenReturn(List.of(x, y));
    when(products.variantInfo(any())).thenReturn(Map.of());
    // the inventory knows neither product at all
    org.mockito.Mockito.doAnswer(inv -> new HashMap<UUID, Stock>()).when(inventory).stocks(any());

    var out = service.group(g);

    assertThat(out.defaultVariantId()).isEqualTo(b);
    assertThat(out.inStock()).isFalse();
    assertThat(out.imageUrl()).isNull();
    assertThat(out.rating()).isZero();
    assertThat(out.variants()).allSatisfy(v -> {
      assertThat(v.qtyAvailable()).isZero();
      assertThat(v.variantName()).isEmpty();
    });
  }

  @ParameterizedTest
  @CsvSource(nullValues = "null", value = {
      "false, null, null, false",
      "true, null, null, true",
      "true, FUTURE, null, false",
      "true, PAST, null, true",
      "true, null, PAST, false",
      "true, null, FUTURE, true",
      "true, PAST, FUTURE, true",
      "true, PAST, PAST, false"})
  void aGroupIsHotOnlyInsideItsHotWindow(boolean hotFlag, String start, String end, boolean expected) {
    UUID g = UUID.randomUUID();
    var d = pd(g, g, 100, "1 kg", "/i.jpg", true, hotFlag, at(start), at(end), 0, 0);
    when(products.allMembersOf(g)).thenReturn(List.of(d));
    when(products.variantInfo(any())).thenReturn(Map.of());
    assertThat(service.group(g).hotSale()).isEqualTo(expected);
  }

  private static Instant at(String s) { return s == null ? null : s.equals("PAST") ? PAST : FUTURE; }

  @Test
  void groupRejectsEmptyInactiveAndCounterOnlyProducts() {
    UUID g = UUID.randomUUID(), inactive = UUID.randomUUID(), counter = UUID.randomUUID();
    when(products.allMembersOf(g)).thenReturn(List.of());
    assertThatThrownBy(() -> service.group(g)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));

    UUID g2 = UUID.randomUUID();
    when(products.allMembersOf(g2)).thenReturn(List.of(pd(inactive, g2, 100, "1", "/i", false, false, null, null, 0, 0),
        pd(counter, g2, 100, "2", "/i", true, false, null, null, 0, 0)));
    when(products.isPosOnly(counter)).thenReturn(true);
    assertThatThrownBy(() -> service.group(g2)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
  }

  @Test
  void groupsHidesSoldOutVariantsAndProductsByDefaultAndShowsThemWhenAsked() {
    UUID g1 = UUID.randomUUID(), g2 = UUID.randomUUID(), g3 = UUID.randomUUID();
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    var ra = simple(a, g1);
    var rb = pd(b, g1, 150, "2 kg", "/b", true, false, null, null, 0, 0);
    var rc = simple(c, g2);
    stock.put(a, new Stock(a, 10, 5));
    when(products.searchGroups(any(), any(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt()))
        .thenAnswer(inv -> new Page<>(List.of(g1, g2, g3), 0, 20, 3));
    when(products.visibleMembers(any())).thenReturn(List.of(ra, rb, rc));
    when(products.variantInfo(any())).thenReturn(Map.of());

    var hidden = service.groups("m", cat, true, -4, 20);

    verify(products).searchGroups("m", cat, true, true, NOW, 0, 20);
    assertThat(hidden.items()).hasSize(1);
    assertThat(hidden.items().get(0).groupId()).isEqualTo(g1);
    assertThat(hidden.items().get(0).variants()).extracting(CatalogService.Variant::id).containsExactly(a);
    assertThat(hidden.total()).isEqualTo(3);

    var all = service.groups("m", cat, true, true, 2, 99);

    verify(products).searchGroups("m", cat, true, false, NOW, 2, 50);
    assertThat(all.items()).extracting(CatalogService.ProductGroup::groupId).containsExactly(g1, g2);
    assertThat(all.items().get(0).variants()).hasSize(2);
  }

  // ---------- stock movements ----------

  @Test
  void stockMovementsNameTheProductAndReferenceOrFallBack() {
    UUID known = UUID.randomUUID(), gone = UUID.randomUUID(), order = UUID.randomUUID();
    var d = simple(known, known);
    var m1 = new Movement(UUID.randomUUID(), known, -2, "SALE", order, actor, NOW);
    var m2 = new Movement(UUID.randomUUID(), gone, 5, "RESTOCK", null, actor, NOW);
    when(inventory.movements(null, 0, 20)).thenReturn(new Page<>(List.of(m1, m2), 0, 20, 2));
    when(products.findAll(any())).thenReturn(List.of(d));
    when(references.of(any())).thenReturn(Map.of(order, "VSM-1001"));

    var out = service.stockMovements(null, 0, 20);

    assertThat(out.items().get(0).name()).isEqualTo("Mango");
    assertThat(out.items().get(0).packLabel()).isEqualTo("1 kg");
    assertThat(out.items().get(0).ref()).isEqualTo("VSM-1001");
    assertThat(out.items().get(1).name()).isEqualTo("?");
    assertThat(out.items().get(1).packLabel()).isEmpty();
    assertThat(out.items().get(1).ref()).isNull();
    assertThat(out.items().get(1).refId()).isNull();
    assertThat(out.total()).isEqualTo(2);
  }

  // ---------- admin variants ----------

  @Test
  void adminVariantsListsEveryVariantWithStockOrFailsWhenNoneExist() {
    UUID g = UUID.randomUUID(), a = UUID.randomUUID();
    stock.put(a, new Stock(a, 7, 5));
    when(products.allMembersOf(g)).thenReturn(List.of());
    assertThatThrownBy(() -> service.adminVariants(g)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));

    UUID g2 = UUID.randomUUID();
    when(products.allMembersOf(g2)).thenReturn(List.of(simple(a, g2)));
    var out = service.adminVariants(g2);
    assertThat(out).hasSize(1);
    assertThat(out.get(0).qtyOnHand()).isEqualTo(7);
  }

  // ---------- addVariant ----------

  private ProductData base(UUID id, UUID group) {
    return pd(id, group, 100, "1 kg", "/i", true, true, PAST, FUTURE, 0, 0);
  }

  private void stubInsert(UUID newId, UUID group) {
    when(products.insert(any(), any(), any(), any(), anyLong(), anyLong(), any(), any(), anyBoolean(), anyBoolean(), any(),
        any(), anyBoolean(), anyBoolean(), any())).thenAnswer(inv -> {
      pd(newId, group, 120, inv.getArgument(7), null, inv.getArgument(8), false, null, null, 0, 0);
      return newId;
    });
  }

  @Test
  void addVariantCopiesSharedDetailsAppendsAfterTheLastOrderAndAppliesDefaults() {
    UUID g = UUID.randomUUID(), baseId = UUID.randomUUID(), other = UUID.randomUUID(), created = UUID.randomUUID();
    base(baseId, g);
    var otherRow = pd(other, g, 90, "250 g", null, true, false, null, null, 0, 0);
    when(products.allMembersOf(g)).thenReturn(List.of(db.get(baseId), otherRow));
    when(products.variantInfo(any())).thenReturn(Map.of(baseId, new VariantInfo("Std", 3), other, new VariantInfo("Mini", 1)));
    stubInsert(created, g);

    var out = service.addVariant(baseId, new CatalogService.VariantInput(" Big ", " 2 kg ", null, 300, 250, null, 8, 0, null), actor);

    assertThat(out.id()).isEqualTo(created);
    verify(products).insert(eq(cat), eq("Mango"), eq("કેરી"), eq("desc"), eq(300L), eq(250L), eq("kg"), eq("2 kg"), eq(true),
        eq(true), eq(PAST), eq(FUTURE), eq(true), eq(false), any());
    verify(products).setGroup(created, g);
    verify(inventory).create(created, 8, 5);
    verify(products).setVariant(created, "Big", 4);
  }

  @Test
  void addVariantHonoursAnExplicitOrderUnitThresholdAndInactiveFlag() {
    UUID g = UUID.randomUUID(), baseId = UUID.randomUUID(), created = UUID.randomUUID();
    base(baseId, g);
    when(products.allMembersOf(g)).thenReturn(List.of(db.get(baseId)));
    when(products.variantInfo(any())).thenReturn(Map.of());
    stubInsert(created, g);

    service.addVariant(baseId, new CatalogService.VariantInput(null, "5 kg", "g", 600, 500, false, 2, 9, 7), actor);

    verify(products).insert(eq(cat), any(), any(), any(), eq(600L), eq(500L), eq("g"), eq("5 kg"), eq(false), anyBoolean(),
        any(), any(), anyBoolean(), anyBoolean(), any());
    verify(inventory).create(created, 2, 9);
    verify(products).setVariant(created, "", 7);
  }

  @Test
  void firstVariantOfAProductWithNoInfoGetsOrderZero() {
    UUID g = UUID.randomUUID(), baseId = UUID.randomUUID(), created = UUID.randomUUID();
    base(baseId, g);
    when(products.allMembersOf(g)).thenReturn(List.of());
    when(products.variantInfo(any())).thenReturn(Map.of());
    stubInsert(created, g);
    service.addVariant(baseId, new CatalogService.VariantInput("Only", null, null, 100, 100, true, 1, 1, null), actor);
    verify(products).setVariant(created, "Only", 0);
  }

  @Test
  void addVariantNeedsANameOrPackSizeAndRefusesDuplicates() {
    UUID g = UUID.randomUUID(), baseId = UUID.randomUUID();
    base(baseId, g);
    when(products.allMembersOf(g)).thenReturn(List.of(db.get(baseId)));
    when(products.variantInfo(any())).thenReturn(Map.of(baseId, new VariantInfo("Std", 0)));

    assertThatThrownBy(() -> service.addVariant(baseId, new CatalogService.VariantInput(" ", null, null, 1, 1, null, 0, 0, null), actor))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    assertThatThrownBy(() -> service.addVariant(baseId, new CatalogService.VariantInput("STD", "1 KG", null, 1, 1, null, 0, 0, null), actor))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    // same pack size but a different name, and the same name with another pack size, are both allowed to pass the duplicate check
    UUID created = UUID.randomUUID();
    stubInsert(created, g);
    service.addVariant(baseId, new CatalogService.VariantInput("Other", "1 kg", null, 100, 100, null, 0, 0, null), actor);
    service.addVariant(baseId, new CatalogService.VariantInput("Std", "2 kg", null, 100, 100, null, 0, 0, null), actor);
    verify(products, org.mockito.Mockito.times(2)).setGroup(created, g);
  }

  @Test
  void addVariantToAnUnknownProductIsNotFound() {
    assertThatThrownBy(() -> service.addVariant(UUID.randomUUID(),
        new CatalogService.VariantInput("a", "b", null, 1, 1, null, 0, 0, null), actor))
        .satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
  }

  // ---------- setVariant ----------

  @Test
  void setVariantRenamesTrimsAndReordersOrKeepsWhatIsNull() {
    UUID id = UUID.randomUUID();
    simple(id, id);
    when(products.variantInfo(any())).thenReturn(Map.of(id, new VariantInfo("Old", 4)));

    service.setVariant(id, "  New ", 9, actor);
    verify(products).setVariant(id, "New", 9);
    verify(audit).log(actor, "UPDATE", "product", id, "variant New");

    service.setVariant(id, null, null, actor);
    verify(products).setVariant(id, "Old", 4);
    verify(audit).log(actor, "UPDATE", "product", id, "variant Old");
  }

  @Test
  void setVariantWithNoStoredInfoKeepsEmptyNameAndOrderZero() {
    UUID id = UUID.randomUUID();
    simple(id, id);
    when(products.variantInfo(any())).thenReturn(Map.of());
    service.setVariant(id, null, null, actor);
    verify(products).setVariant(id, "", 0);
  }

  @Test
  void setVariantOfAnUnknownProductIsNotFound() {
    assertThatThrownBy(() -> service.setVariant(UUID.randomUUID(), "x", 1, actor)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
    verify(products, never()).setVariant(any(), any(), anyInt());
  }

  // ---------- updateGroup ----------

  @Test
  void updateGroupWritesSharedDetailsWithDefaultsForMissingFlags() {
    UUID g = UUID.randomUUID(), a = UUID.randomUUID();
    var row = simple(a, g);
    when(products.allMembersOf(g)).thenReturn(List.of(row));

    var out = service.updateGroup(g, new CatalogService.GroupInput(cat, "Mango", "કેરી", null, null, null, null, null, null, null), actor);

    verify(products).updateShared(g, cat, "Mango", "કેરી", "", false, null, null, true, true, null, NOW);
    verify(audit).log(actor, "UPDATE", "product_group", g, "Mango");
    assertThat(out).hasSize(1);
  }

  @Test
  void updateGroupPassesExplicitFlagsAndAValidHotWindow() {
    UUID g = UUID.randomUUID(), a = UUID.randomUUID();
    var row = simple(a, g);
    when(products.allMembersOf(a)).thenReturn(List.of(row));
    when(products.allMembersOf(g)).thenReturn(List.of(row));

    service.updateGroup(a, new CatalogService.GroupInput(cat, "M", "G", "d", true, PAST, FUTURE, false, false, false), actor);
    verify(products).updateShared(g, cat, "M", "G", "d", true, PAST, FUTURE, false, false, false, NOW);

    service.updateGroup(a, new CatalogService.GroupInput(cat, "M", "G", "d", false, PAST, null, true, true, true), actor);
    verify(products).updateShared(g, cat, "M", "G", "d", false, PAST, null, true, true, true, NOW);

    service.updateGroup(a, new CatalogService.GroupInput(cat, "M", "G", "d", false, null, FUTURE, true, true, true), actor);
    verify(products).updateShared(g, cat, "M", "G", "d", false, null, FUTURE, true, true, true, NOW);
  }

  @Test
  void updateGroupRejectsUnknownProductsAndAnEndBeforeTheStart() {
    UUID g = UUID.randomUUID(), a = UUID.randomUUID();
    when(products.allMembersOf(g)).thenReturn(List.of());
    assertThatThrownBy(() -> service.updateGroup(g, new CatalogService.GroupInput(cat, "M", "G", "d", null, null, null, null, null, null), actor))
        .satisfies(t -> assertApi(t, 404, "NOT_FOUND"));

    UUID g2 = UUID.randomUUID();
    when(products.allMembersOf(g2)).thenReturn(List.of(simple(a, g2)));
    assertThatThrownBy(() -> service.updateGroup(g2, new CatalogService.GroupInput(cat, "M", "G", "d", true, FUTURE, PAST, null, null, null), actor))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    verify(products, never()).updateShared(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean(),
        anyBoolean(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void aGroupShowsTheFirstFlashOfTheVariantsThatHaveOne() {
    UUID g = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    var x = pd(a, g, 300, "1 kg", null, true, false, null, null, 0, 0);
    var y = pd(b, g, 100, "250 g", null, true, false, null, null, 0, 0);
    stock.put(a, new Stock(a, 50, 5));
    stock.put(b, new Stock(b, 50, 5));
    when(products.allMembersOf(g)).thenReturn(List.of(x, y));
    when(products.variantInfo(any())).thenReturn(Map.of());
    var flash = mock(com.vijay.mall.common.FlashPricing.class);
    // only the variant listed last is on sale, so the first one is skipped when picking the group's flash
    when(flash.offers(any())).thenAnswer(inv -> {
      Map<UUID, Long> prices = inv.getArgument(0);
      UUID last = prices.keySet().stream().reduce((p, q) -> q).orElseThrow();
      return Map.of(last, new com.vijay.mall.common.FlashPricing.Offer(UUID.randomUUID(), "Sale", 100, 50, 50, null, 0, PAST, FUTURE));
    });
    service.setFlash(flash);

    var out = service.group(g);

    assertThat(out.flash()).isNotNull();
    assertThat(out.variants().get(0).flash()).isNull();
    assertThat(out.variants().get(1).flash()).isEqualTo(out.flash());
  }

  @Test
  void hotSaleKeepsOnlyBannersThatAreOnRightNow() {
    var banners = mock(BannerRepository.class);
    var svc = new CatalogService(products, mock(CategoryRepository.class), banners, inventory, audit, Clock.fixed(NOW, ZoneOffset.UTC), references);
    UUID i = UUID.randomUUID();
    var off = new com.vijay.mall.catalog.domain.HotBanner(i, "off", "", null, null, null, false);
    var open = new com.vijay.mall.catalog.domain.HotBanner(i, "open", "", null, null, null, true);
    var future = new com.vijay.mall.catalog.domain.HotBanner(i, "future", "", null, FUTURE, null, true);
    var past = new com.vijay.mall.catalog.domain.HotBanner(i, "past", "", null, PAST, null, true);
    var ended = new com.vijay.mall.catalog.domain.HotBanner(i, "ended", "", null, null, PAST, true);
    var running = new com.vijay.mall.catalog.domain.HotBanner(i, "running", "", null, PAST, FUTURE, true);
    var startsNow = new com.vijay.mall.catalog.domain.HotBanner(i, "startsNow", "", null, NOW, NOW, true);
    var endsFuture = new com.vijay.mall.catalog.domain.HotBanner(i, "endsFuture", "", null, null, FUTURE, true);
    when(banners.all()).thenReturn(List.of(off, open, future, past, ended, running, startsNow, endsFuture));
    when(products.searchInStock(any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 0, 20, 0));

    assertThat(svc.hotSale().banners()).containsExactly(open, past, running, startsNow, endsFuture);
  }
}
