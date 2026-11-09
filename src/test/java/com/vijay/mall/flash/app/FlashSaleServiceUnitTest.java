package com.vijay.mall.flash.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.flash.app.FlashSaleService.CreateInput;
import com.vijay.mall.flash.app.FlashSaleService.DuplicateInput;
import com.vijay.mall.flash.app.FlashSaleService.ItemInput;
import com.vijay.mall.flash.app.FlashSaleService.UpdateInput;
import com.vijay.mall.flash.app.FlashViews.SaleView;
import com.vijay.mall.flash.app.FlashViews.ShopSales;
import com.vijay.mall.flash.domain.FlashKind;
import com.vijay.mall.flash.domain.FlashMath;
import com.vijay.mall.flash.infra.FlashRepository.Clash;
import com.vijay.mall.flash.infra.FlashRepository.Item;
import com.vijay.mall.flash.infra.FlashRepository.Sale;
import com.vijay.mall.flash.infra.FlashRepository.SaleWithItems;
import com.vijay.mall.flash.infra.FlashRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/** Every validation, status guard and view branch of FlashSaleService, with the repository and catalog mocked. */
@SuppressWarnings("unchecked")
class FlashSaleServiceUnitTest {
  static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
  static final Instant PAST = NOW.minus(Duration.ofHours(1));
  static final Instant LATER = NOW.plus(Duration.ofHours(1));
  static final UUID ACTOR = UUID.randomUUID();
  static final UUID SALE = UUID.randomUUID();
  static final UUID P1 = UUID.randomUUID();
  static final UUID P2 = UUID.randomUUID();
  static final UUID ITEM = UUID.randomUUID();

  FlashRepository repo = mock(FlashRepository.class);
  CatalogService catalog = mock(CatalogService.class);
  AuditService audit = mock(AuditService.class);
  LiveEvents live = mock(LiveEvents.class);
  FlashPricingService pricing = mock(FlashPricingService.class);
  FlashSaleService svc;

  Map<UUID, ProductData> db = new HashMap<>();
  Sale current;
  List<Item> currentItems = new ArrayList<>();

  @BeforeEach
  void setUp() {
    svc = new FlashSaleService(repo, catalog, audit, live, Clock.fixed(NOW, ZoneOffset.UTC), pricing);
    db.put(P1, product(P1, 10000, true));
    db.put(P2, product(P2, 20000, true));
    when(catalog.posProducts(anyCollection())).thenAnswer(inv -> ((Collection<UUID>) inv.getArgument(0)).stream()
        .map(db::get).filter(Objects::nonNull).toList());
    current = sale(PAST, LATER, null);
    when(repo.findSale(any())).thenAnswer(inv -> Optional.ofNullable(current));
    when(repo.items(any())).thenAnswer(inv -> currentItems);
  }

  static ProductData product(UUID id, long price, boolean active) {
    return new ProductData(id, UUID.randomUUID(), "Prod " + id.toString().substring(0, 4), "gu", "d", price, price, "pack", "1 pc", null, active,
        false, null, null, true, true);
  }

  static Sale sale(Instant start, Instant end, Instant cancelled) {
    return new Sale(SALE, "Sale", "desc", "banner", start, end, "BOTH", "PERCENT", 20, 0, cancelled, ACTOR, PAST, PAST);
  }

  static Item item(int sold, Integer max) { return new Item(ITEM, SALE, P1, null, 0, max, sold, null); }

  static ItemInput in(UUID product) { return new ItemInput(product, null, null, null, null); }

  static CreateInput create(String name, String kind, Long value, Integer cust, String channel, List<ItemInput> items) {
    return new CreateInput(name, null, null, null, LATER, channel, kind, value, cust, items);
  }

  static CreateInput ok() { return create("Sale", "PERCENT", 20L, null, null, List.of(in(P1))); }

  void scheduled() { current = sale(LATER, LATER.plus(Duration.ofHours(2)), null); }

  void rejects(Runnable r, String code, String text) {
    assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(code);
      assertThat(e.getMessage()).contains(text);
    });
  }

  void invalid(CreateInput c, String text) { rejects(() -> svc.create(c, ACTOR), "VALIDATION_FAILED", text); }

  // ---------- create ----------
  @Test
  void createSucceedsWithDefaults() {
    SaleView v = svc.create(ok(), ACTOR);
    assertThat(v).isNotNull();
    ArgumentCaptor<Sale> s = ArgumentCaptor.forClass(Sale.class);
    verify(repo).insertSale(s.capture());
    assertThat(s.getValue().startsAt()).isEqualTo(NOW);
    assertThat(s.getValue().channel()).isEqualTo("BOTH");
    assertThat(s.getValue().perCustomerLimit()).isZero();
    verify(live).area(any());
  }

  @Test
  void createValidatesTheSaleFields() {
    invalid(create(null, "PERCENT", 20L, null, null, List.of(in(P1))), "name must be");
    invalid(create("  ", "PERCENT", 20L, null, null, List.of(in(P1))), "name must be");
    invalid(create("x".repeat(81), "PERCENT", 20L, null, null, List.of(in(P1))), "name must be");
    invalid(create("S", "BOGUS", 20L, null, null, List.of(in(P1))), "discount kind");
    invalid(create("S", "PERCENT", null, null, null, List.of(in(P1))), "percent must be");
    invalid(create("S", "PERCENT", 91L, null, null, List.of(in(P1))), "percent must be");
    invalid(create("S", "PERCENT", 20L, -1, null, List.of(in(P1))), "0 to 999");
    invalid(create("S", "PERCENT", 20L, 1000, null, List.of(in(P1))), "0 to 999");
    invalid(create("S", "PERCENT", 20L, null, "MOON", List.of(in(P1))), "ONLINE, POS or BOTH");
    invalid(create("S", "PERCENT", 20L, null, null, null), "at least one product");
    invalid(create("S", "PERCENT", 20L, null, null, List.of()), "at least one product");
    invalid(create("S", "PERCENT", 20L, null, null, IntStream.range(0, 201).mapToObj(i -> in(UUID.randomUUID())).toList()), "at most 200");
    invalid(new CreateInput("S", "d".repeat(301), null, null, LATER, null, "PERCENT", 20L, null, List.of(in(P1))), "at most 300");
    invalid(new CreateInput("S", null, "b".repeat(81), null, LATER, null, "PERCENT", 20L, null, List.of(in(P1))), "at most 80");
    invalid(new CreateInput("S", null, null, null, null, null, "PERCENT", 20L, null, List.of(in(P1))), "endsAt is required");
    invalid(new CreateInput("S", null, null, PAST, LATER, null, "PERCENT", 20L, null, List.of(in(P1))), "startsAt");
  }

  @Test
  void createAcceptsLowerCaseChannelAndBlankChannel() {
    svc.create(create("S", "PERCENT", 20L, 5, " online ", List.of(in(P1))), ACTOR);
    svc.create(create("S", "PERCENT", 20L, 0, " ", List.of(in(P1))), ACTOR);
    svc.create(create("S", "percent", 20L, 999, "pos", List.of(in(P1))), ACTOR);
    ArgumentCaptor<Sale> s = ArgumentCaptor.forClass(Sale.class);
    verify(repo, Mockito.times(3)).insertSale(s.capture());
    assertThat(s.getAllValues()).extracting(Sale::channel).containsExactly("ONLINE", "BOTH", "POS");
  }

  @Test
  void createWithoutADefaultDiscountNeedsEveryItemToHaveOne() {
    invalid(create("S", null, null, null, null, List.of(in(P1))), "needs its own discount");
    invalid(create("S", "NONE", null, null, null, List.of(in(P1))), "needs its own discount");
    svc.create(create("S", null, null, null, null, List.of(new ItemInput(P1, "PERCENT", 10L, null, null))), ACTOR);
    svc.create(create("S", null, null, null, null, List.of(new ItemInput(P1, "FIXED_PRICE", 5000L, 7, 3))), ACTOR);
    ArgumentCaptor<Item> i = ArgumentCaptor.forClass(Item.class);
    verify(repo, Mockito.times(2)).insertItem(i.capture(), eq(NOW));
    assertThat(i.getAllValues().get(0).discountKind()).isEqualTo("PERCENT");
    assertThat(i.getAllValues().get(1).discountValue()).isEqualTo(5000);
    assertThat(i.getAllValues().get(1).maxQty()).isEqualTo(7);
  }

  @Test
  void createValidatesEachItem() {
    invalid(create("S", "PERCENT", 20L, null, null, List.of(in(null))), "every item needs a productId");
    invalid(create("S", "PERCENT", 20L, null, null, List.of(in(P1), in(P1))), "only once");
    invalid(create("S", "PERCENT", 20L, null, null, List.of(in(UUID.randomUUID()))), "unknown product");
    db.put(P2, product(P2, 20000, false));
    invalid(create("S", "PERCENT", 20L, null, null, List.of(in(P2))), "is not active");
    invalid(create("S", "PERCENT", 20L, null, null, List.of(new ItemInput(P1, "BOGUS", 1L, null, null))), "discount kind");
    invalid(create("S", "PERCENT", 20L, null, null, List.of(new ItemInput(P1, "PERCENT", 95L, null, null))), "percent must be");
    invalid(create("S", "PERCENT", 20L, null, null, List.of(new ItemInput(P1, "FIXED_PRICE", 10000L, null, null))), "no lower price");
    db.put(P1, product(P1, 100, true));
    invalid(create("S", "PERCENT", 20L, null, null, List.of(in(P1))), "no lower price");
    db.put(P1, product(P1, 10000, true));
    invalid(create("S", "PERCENT", 20L, null, null, List.of(new ItemInput(P1, null, null, 0, null))), "maxQty");
    invalid(create("S", "PERCENT", 20L, null, null, List.of(new ItemInput(P1, null, null, FlashSaleService.MAX_QTY + 1, null))), "maxQty");
    invalid(create("S", "PERCENT", 20L, null, null, List.of(new ItemInput(P1, null, null, null, -1))), "perCustomerLimit");
    invalid(create("S", "PERCENT", 20L, null, null, List.of(new ItemInput(P1, null, null, null, 1000))), "perCustomerLimit");
  }

  @Test
  void blankOrNoneItemKindFollowsTheSale() {
    svc.create(create("S", "PERCENT", 20L, null, null, List.of(new ItemInput(P1, "  ", 5L, FlashSaleService.MAX_QTY, 0))), ACTOR);
    svc.create(create("S", "PERCENT", 20L, null, null, List.of(new ItemInput(P2, "NONE", 5L, 1, 999))), ACTOR);
    ArgumentCaptor<Item> i = ArgumentCaptor.forClass(Item.class);
    verify(repo, Mockito.times(2)).insertItem(i.capture(), eq(NOW));
    assertThat(i.getAllValues()).allSatisfy(x -> assertThat(x.discountKind()).isNull());
  }

  @Test
  void createRefusesAClashWithAnotherSale() {
    UUID other = UUID.randomUUID();
    when(repo.clashes(anyCollection(), any(), any(), any())).thenReturn(List.of(new Clash(other, "Other", P1)));
    when(catalog.names(anyCollection())).thenReturn(Map.of(P1, "Rice"));
    assertThatThrownBy(() -> svc.create(ok(), ACTOR)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("FLASH_OVERLAP");
      assertThat(e.getMessage()).contains("Rice").contains("Other");
    });
    when(catalog.names(anyCollection())).thenReturn(Map.of());
    assertThatThrownBy(() -> svc.create(ok(), ACTOR)).hasMessageContaining(P1.toString());
    verify(repo, never()).insertSale(any());
  }

  // ---------- update ----------
  @Test
  void updateWithNothingChangesNothing() {
    scheduled();
    svc.update(SALE, new UpdateInput(null, null, null, null, null, null, null, null, null), ACTOR);
    verify(audit).log(eq(ACTOR), eq("FLASH_UPDATE"), anyString(), eq(SALE), eq("no change"));
    verify(repo, never()).clashes(anyCollection(), any(), any(), any());
  }

  @Test
  void updateChangesEveryFieldOfAScheduledSale() {
    scheduled();
    currentItems = List.of(item(0, 5));
    Instant start = LATER.plus(Duration.ofHours(5));
    svc.update(SALE, new UpdateInput("New", "d2", "b2", start, start.plus(Duration.ofHours(1)), "pos", "FIXED_PRICE", 5000L, 3), ACTOR);
    ArgumentCaptor<String> change = ArgumentCaptor.forClass(String.class);
    verify(audit).log(eq(ACTOR), eq("FLASH_UPDATE"), anyString(), eq(SALE), change.capture());
    assertThat(change.getValue()).contains("name 'Sale' -> 'New'").contains("description").contains("bannerText").contains("start ")
        .contains("end ").contains("channel BOTH -> POS").contains("discount PERCENT 20 -> FIXED_PRICE 5000").contains("perCustomerLimit 0 -> 3");
    verify(repo).clashes(anyCollection(), eq(start), any(), eq(SALE));
  }

  @Test
  void updateDescribesAValueOnlyAndAKindOnlyChange() {
    scheduled();
    currentItems = List.of(item(0, null));
    svc.update(SALE, new UpdateInput(null, null, null, null, null, null, null, 30L, null), ACTOR);
    verify(audit).log(eq(ACTOR), eq("FLASH_UPDATE"), anyString(), eq(SALE), contains("discount PERCENT 20 -> PERCENT 30"));
    current = new Sale(SALE, "Sale", "desc", "banner", LATER, LATER.plus(Duration.ofHours(2)), "BOTH", "FIXED_PRICE", 5000, 0, null, ACTOR, PAST, PAST);
    svc.update(SALE, new UpdateInput(null, null, null, null, null, null, "PERCENT", 20L, null), ACTOR);
    verify(audit).log(eq(ACTOR), eq("FLASH_UPDATE"), anyString(), eq(SALE), contains("discount FIXED_PRICE 5000 -> PERCENT 20"));
    // same kind and same value given explicitly: nothing to describe
    svc.update(SALE, new UpdateInput(null, null, null, null, null, null, "FIXED_PRICE", 5000L, null), ACTOR);
    verify(audit).log(eq(ACTOR), eq("FLASH_UPDATE"), anyString(), eq(SALE), eq("no change"));
  }

  @Test
  void updateRevalidatesTheItemsWhenTheDefaultDiscountChanges() {
    scheduled();
    currentItems = List.of(item(0, null));
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, null, null, null, null, "FIXED_PRICE", 10000L, null), ACTOR), "VALIDATION_FAILED",
        "no lower price");
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, null, null, null, null, "NONE", null, null), ACTOR), "VALIDATION_FAILED",
        "needs its own discount");
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, null, null, null, null, "FIXED_PRICE", null, null), ACTOR), "VALIDATION_FAILED",
        "sale price must be");
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, null, null, null, null, "BOGUS", null, null), ACTOR), "VALIDATION_FAILED", "kind");
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, null, null, null, null, null, null, 1000), ACTOR), "VALIDATION_FAILED", "0 to 999");
    rejects(() -> svc.update(SALE, new UpdateInput("", null, null, null, null, null, null, null, null), ACTOR), "VALIDATION_FAILED", "name");
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, null, null, null, "MOON", null, null, null), ACTOR), "VALIDATION_FAILED", "ONLINE, POS");
    rejects(() -> svc.update(SALE, new UpdateInput(null, "d".repeat(301), null, null, null, null, null, null, null), ACTOR), "VALIDATION_FAILED",
        "at most 300");
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, "b".repeat(81), null, null, null, null, null, null), ACTOR), "VALIDATION_FAILED",
        "at most 80");
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, null, LATER, LATER.minusSeconds(1), null, null, null, null), ACTOR),
        "VALIDATION_FAILED", "endsAt");
    verify(repo, never()).updateSale(any());
  }

  @Test
  void updateRefusesAClashWhenTheTimesMove() {
    scheduled();
    currentItems = List.of(item(0, null));
    when(repo.clashes(anyCollection(), any(), any(), any())).thenReturn(List.of(new Clash(UUID.randomUUID(), "Other", P1)));
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, null, LATER.plusSeconds(60), null, null, null, null, null), ACTOR), "FLASH_OVERLAP",
        "Other");
    rejects(() -> svc.update(SALE, new UpdateInput(null, null, null, null, LATER.plus(Duration.ofHours(9)), null, null, null, null), ACTOR),
        "FLASH_OVERLAP", "Other");
  }

  @Test
  void aRunningSaleCanBeRenamedAndExtendedButNotReconfigured() {
    currentItems = List.of(item(2, 5));
    Instant end = LATER.plus(Duration.ofHours(1));
    svc.update(SALE, new UpdateInput("Renamed", null, null, current.startsAt(), end, "both", "percent", 20L, 0), ACTOR);
    verify(repo).updateSale(any());
    UpdateInput[] touching = {
        new UpdateInput(null, null, null, PAST.plusSeconds(1), null, null, null, null, null),
        new UpdateInput(null, null, null, null, null, "ONLINE", null, null, null),
        new UpdateInput(null, null, null, null, null, null, "FIXED_PRICE", 5000L, null),
        new UpdateInput(null, null, null, null, null, null, null, 30L, null),
        new UpdateInput(null, null, null, null, null, null, null, null, 4)};
    for (UpdateInput u : touching) rejects(() -> svc.update(SALE, u, ACTOR), "FLASH_RUNNING", "running sale");
  }

  @Test
  void anEndedOrCancelledSaleCannotBeChanged() {
    current = sale(PAST.minus(Duration.ofHours(2)), PAST, null);
    rejects(() -> svc.update(SALE, new UpdateInput("x", null, null, null, null, null, null, null, null), ACTOR), "FLASH_NOT_ALLOWED", "ENDED");
    current = sale(PAST, LATER, NOW);
    rejects(() -> svc.update(SALE, new UpdateInput("x", null, null, null, null, null, null, null, null), ACTOR), "FLASH_NOT_ALLOWED", "CANCELLED");
    rejects(() -> svc.addItem(SALE, in(P2), ACTOR), "FLASH_NOT_ALLOWED", "CANCELLED");
    rejects(() -> svc.removeItem(SALE, ITEM, ACTOR), "FLASH_NOT_ALLOWED", "CANCELLED");
    rejects(() -> svc.updateItem(SALE, ITEM, in(P1), ACTOR), "FLASH_NOT_ALLOWED", "CANCELLED");
    rejects(() -> svc.cancel(SALE, ACTOR), "FLASH_NOT_ALLOWED", "CANCELLED");
    rejects(() -> svc.endNow(SALE, ACTOR), "FLASH_NOT_ALLOWED", "CANCELLED");
    rejects(() -> svc.startNow(SALE, ACTOR), "FLASH_NOT_ALLOWED", "CANCELLED");
  }

  @Test
  void anUnknownSaleIsNotFound() {
    current = null;
    assertThatThrownBy(() -> svc.get(SALE)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
    assertThatThrownBy(() -> svc.duplicate(SALE, null, ACTOR)).isInstanceOf(ApiException.class);
  }

  // ---------- items ----------
  @Test
  void addItemAddsAProduct() {
    currentItems = List.of(item(0, null));
    svc.addItem(SALE, new ItemInput(P2, null, null, 4, 2), ACTOR);
    ArgumentCaptor<Item> i = ArgumentCaptor.forClass(Item.class);
    verify(repo).insertItem(i.capture(), eq(NOW));
    assertThat(i.getValue().productId()).isEqualTo(P2);
    verify(repo).updateSale(any());
  }

  @Test
  void addItemRefusesBadInput() {
    currentItems = List.of(item(0, null));
    rejects(() -> svc.addItem(SALE, in(P1), ACTOR), "VALIDATION_FAILED", "already in the sale");
    rejects(() -> svc.addItem(SALE, in(null), ACTOR), "VALIDATION_FAILED", "unknown product");
    rejects(() -> svc.addItem(SALE, in(UUID.randomUUID()), ACTOR), "VALIDATION_FAILED", "unknown product");
    currentItems = IntStream.range(0, 200).mapToObj(i -> new Item(UUID.randomUUID(), SALE, UUID.randomUUID(), null, 0, null, 0, null)).toList();
    rejects(() -> svc.addItem(SALE, in(P2), ACTOR), "VALIDATION_FAILED", "at most 200");
    currentItems = List.of();
    when(repo.clashes(anyCollection(), any(), any(), any())).thenReturn(List.of(new Clash(UUID.randomUUID(), "Other", P2)));
    rejects(() -> svc.addItem(SALE, in(P2), ACTOR), "FLASH_OVERLAP", "Other");
  }

  @Test
  void removeItemOnlyWhenNothingWasSold() {
    currentItems = List.of(item(0, null));
    when(repo.findItem(ITEM)).thenReturn(Optional.of(item(0, null)));
    svc.removeItem(SALE, ITEM, ACTOR);
    verify(repo).deleteItem(ITEM);

    when(repo.findItem(ITEM)).thenReturn(Optional.of(item(3, null)));
    rejects(() -> svc.removeItem(SALE, ITEM, ACTOR), "FLASH_ITEM_SOLD", "already been sold");
    when(repo.findItem(ITEM)).thenReturn(Optional.of(item(0, null)));
    when(repo.hasHistory(ITEM)).thenReturn(true);
    rejects(() -> svc.removeItem(SALE, ITEM, ACTOR), "FLASH_ITEM_SOLD", "already been sold");
  }

  @Test
  void anItemOfAnotherSaleOrNoItemIsNotFound() {
    when(repo.findItem(ITEM)).thenReturn(Optional.of(new Item(ITEM, UUID.randomUUID(), P1, null, 0, null, 0, null)));
    rejects(() -> svc.removeItem(SALE, ITEM, ACTOR), "NOT_FOUND", "item");
    when(repo.findItem(ITEM)).thenReturn(Optional.empty());
    rejects(() -> svc.updateItem(SALE, ITEM, in(P1), ACTOR), "NOT_FOUND", "item");
  }

  private void updateItem(Item old, boolean history, ItemInput in) {
    when(repo.findItem(ITEM)).thenReturn(Optional.of(old));
    when(repo.hasHistory(ITEM)).thenReturn(history);
    svc.updateItem(SALE, ITEM, in, ACTOR);
  }

  private void itemLocked(Item old, boolean history, ItemInput in, String text) {
    rejects(() -> updateItem(old, history, in), "FLASH_ITEM_SOLD", text);
  }

  @Test
  void anUnsoldItemCanBeChangedFreely() {
    updateItem(item(0, 5), false, new ItemInput(null, "PERCENT", 30L, 9, 4));
    ArgumentCaptor<Item> i = ArgumentCaptor.forClass(Item.class);
    verify(repo).updateItem(i.capture());
    assertThat(i.getValue().discountKind()).isEqualTo("PERCENT");
    assertThat(i.getValue().maxQty()).isEqualTo(9);
  }

  @Test
  void aSoldItemOnlyAllowsTheQuantityToBeRaised() {
    updateItem(item(5, 10), false, new ItemInput(null, null, null, 15, null));
    updateItem(item(5, 10), false, new ItemInput(null, null, null, 10, null));
    updateItem(item(5, 10), false, new ItemInput(null, null, null, null, null));
    updateItem(item(5, null), false, new ItemInput(null, null, null, null, null));
    updateItem(item(0, 10), true, new ItemInput(null, null, null, 11, null));
    verify(repo, Mockito.times(5)).updateItem(any());

    itemLocked(item(5, 10), false, new ItemInput(null, null, null, 9, null), "can only be raised");
    itemLocked(item(5, null), false, new ItemInput(null, null, null, 9, null), "can only be raised");
    itemLocked(item(0, 10), true, new ItemInput(null, null, null, 2, null), "can only be raised");
    itemLocked(item(5, 10), false, new ItemInput(null, "PERCENT", 10L, 10, null), "only the quantity");
    itemLocked(item(5, 10), false, new ItemInput(null, null, null, 10, 3), "only the quantity");
    Item own = new Item(ITEM, SALE, P1, "PERCENT", 10, 10, 5, null);
    itemLocked(own, false, new ItemInput(null, "PERCENT", 15L, 10, null), "only the quantity");
    itemLocked(own, false, new ItemInput(null, "FIXED_PRICE", 10L * 100, 10, null), "only the quantity");
    updateItem(own, false, new ItemInput(null, "PERCENT", 10L, 12, null));
  }

  @Test
  void updateItemValidatesTheNewSettings() {
    when(repo.findItem(ITEM)).thenReturn(Optional.of(item(0, null)));
    rejects(() -> svc.updateItem(SALE, ITEM, new ItemInput(null, null, null, 0, null), ACTOR), "VALIDATION_FAILED", "maxQty");
    rejects(() -> svc.updateItem(SALE, ITEM, new ItemInput(null, null, null, null, 1000), ACTOR), "VALIDATION_FAILED", "perCustomerLimit");
  }

  // ---------- start / end / cancel ----------
  @Test
  void startNowMovesTheStartToNow() {
    scheduled();
    currentItems = List.of(item(0, null));
    svc.startNow(SALE, ACTOR);
    ArgumentCaptor<Sale> s = ArgumentCaptor.forClass(Sale.class);
    verify(repo).updateSale(s.capture());
    assertThat(s.getValue().startsAt()).isEqualTo(NOW);
  }

  @Test
  void startNowRefusals() {
    rejects(() -> svc.startNow(SALE, ACTOR), "FLASH_NOT_ALLOWED", "ACTIVE");
    current = sale(LATER, NOW.minusSeconds(5), null); // corrupt window: scheduled but the end is already behind us
    rejects(() -> svc.startNow(SALE, ACTOR), "VALIDATION_FAILED", "already passed");
    scheduled();
    currentItems = List.of(item(0, null));
    when(repo.clashes(anyCollection(), any(), any(), any())).thenReturn(List.of(new Clash(UUID.randomUUID(), "Other", P1)));
    rejects(() -> svc.startNow(SALE, ACTOR), "FLASH_OVERLAP", "Other");
  }

  @Test
  void endNowAndCancel() {
    svc.endNow(SALE, ACTOR);
    ArgumentCaptor<Sale> s = ArgumentCaptor.forClass(Sale.class);
    verify(repo).updateSale(s.capture());
    assertThat(s.getValue().endsAt()).isEqualTo(NOW);
    scheduled();
    rejects(() -> svc.endNow(SALE, ACTOR), "FLASH_NOT_ALLOWED", "SCHEDULED");
    svc.cancel(SALE, ACTOR);
    current = sale(PAST, LATER, null);
    svc.cancel(SALE, ACTOR);
    verify(audit, Mockito.times(2)).log(eq(ACTOR), eq("FLASH_CANCEL"), anyString(), eq(SALE), anyString());
  }

  // ---------- duplicate ----------
  @Test
  void duplicateWithoutInputCopiesTheScheduleLength() {
    currentItems = List.of(item(3, 5));
    svc.duplicate(SALE, null, ACTOR);
    ArgumentCaptor<Sale> s = ArgumentCaptor.forClass(Sale.class);
    verify(repo).insertSale(s.capture());
    assertThat(s.getValue().name()).isEqualTo("Sale (copy)");
    assertThat(s.getValue().startsAt()).isEqualTo(NOW);
    assertThat(s.getValue().endsAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
    ArgumentCaptor<Item> i = ArgumentCaptor.forClass(Item.class);
    verify(repo).insertItem(i.capture(), eq(NOW));
    assertThat(i.getValue().soldQty()).isZero();
  }

  @Test
  void duplicateShortensALongNameAndHonoursTheRequest() {
    current = new Sale(SALE, "n".repeat(80), "", "", PAST, LATER, "BOTH", "PERCENT", 20, 0, null, ACTOR, PAST, PAST);
    svc.duplicate(SALE, new DuplicateInput(null, null, null), ACTOR);
    ArgumentCaptor<Sale> s = ArgumentCaptor.forClass(Sale.class);
    verify(repo).insertSale(s.capture());
    assertThat(s.getValue().name()).isEqualTo("n".repeat(73) + " (copy)");

    // exactly at the limit stays whole
    current = new Sale(SALE, "n".repeat(73), "", "", PAST, LATER, "BOTH", "PERCENT", 20, 0, null, ACTOR, PAST, PAST);
    svc.duplicate(SALE, new DuplicateInput(null, null, null), ACTOR);
    verify(repo, Mockito.times(2)).insertSale(s.capture());
    assertThat(s.getValue().name()).isEqualTo("n".repeat(73) + " (copy)");

    Instant start = NOW.plus(Duration.ofDays(1));
    svc.duplicate(SALE, new DuplicateInput(" Again ", start, start.plusSeconds(600)), ACTOR);
    verify(repo, Mockito.times(3)).insertSale(s.capture());
    assertThat(s.getValue().name()).isEqualTo("Again");
    assertThat(s.getValue().endsAt()).isEqualTo(start.plusSeconds(600));

    svc.duplicate(SALE, new DuplicateInput(null, start, null), ACTOR);
    verify(repo, Mockito.times(4)).insertSale(s.capture());
    assertThat(s.getValue().endsAt()).isEqualTo(start.plus(Duration.between(PAST, LATER)));
  }

  @Test
  void duplicateRefusals() {
    rejects(() -> svc.duplicate(SALE, new DuplicateInput("  ", null, null), ACTOR), "VALIDATION_FAILED", "name");
    rejects(() -> svc.duplicate(SALE, new DuplicateInput(null, PAST, null), ACTOR), "VALIDATION_FAILED", "startsAt");
    currentItems = List.of(item(0, null));
    when(repo.clashes(anyCollection(), any(), any(), any())).thenReturn(List.of(new Clash(UUID.randomUUID(), "Other", P1)));
    rejects(() -> svc.duplicate(SALE, null, ACTOR), "FLASH_OVERLAP", "Other");
    db.put(P1, product(P1, 10000, false));
    when(repo.clashes(anyCollection(), any(), any(), any())).thenReturn(List.of());
    rejects(() -> svc.duplicate(SALE, null, ACTOR), "VALIDATION_FAILED", "is not active");
  }

  // ---------- read (staff) ----------
  @Test
  void getShowsItemsWithAndWithoutTheirProduct() {
    UUID gone = UUID.randomUUID();
    currentItems = List.of(
        new Item(UUID.randomUUID(), SALE, gone, null, 0, null, 0, null),
        new Item(UUID.randomUUID(), SALE, P1, "FIXED_PRICE", 5000, 10, 4, 2),
        new Item(UUID.randomUUID(), SALE, P2, null, 0, 3, 9, null));
    SaleView v = svc.get(SALE);
    assertThat(v.items()).hasSize(3);
    var missing = v.items().get(0);
    assertThat(missing.nameEn()).isEqualTo("?");
    assertThat(missing.listPricePaise()).isZero();
    assertThat(missing.salePricePaise()).isNull();
    assertThat(missing.discountPercent()).isNull();
    assertThat(missing.unitsLeft()).isNull();
    assertThat(missing.effectivePerCustomerLimit()).isZero();
    var own = v.items().get(1);
    assertThat(own.salePricePaise()).isEqualTo(5000);
    assertThat(own.discountPercent()).isEqualTo(50);
    assertThat(own.unitsLeft()).isEqualTo(6);
    assertThat(own.effectivePerCustomerLimit()).isEqualTo(2);
    var oversold = v.items().get(2);
    assertThat(oversold.salePricePaise()).isEqualTo(16000);
    assertThat(oversold.unitsLeft()).isZero();
    assertThat(FlashMath.discountPercent(20000, 16000)).isEqualTo(oversold.discountPercent());
  }

  @Test
  void getOfASaleWithoutItems() {
    currentItems = List.of();
    assertThat(svc.get(SALE).items()).isEmpty();
  }

  @Test
  void listFiltersByStatus() {
    when(repo.list(any(), any(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())).thenReturn(List.of(current));
    when(repo.count(any(), any())).thenReturn(1L);
    currentItems = List.of(item(0, null));
    Page<FlashViews.SaleSummary> all = svc.list(null, -3, 1000);
    assertThat(all.items()).hasSize(1);
    assertThat(all.items().get(0).items()).isEqualTo(1);
    assertThat(all.items().get(0).status()).isEqualTo("ACTIVE");
    assertThat(all.page()).isZero();
    assertThat(all.size()).isEqualTo(50);
    verify(repo).list(null, NOW, 50, 0);
    svc.list("  ", 0, 10);
    verify(repo).list(null, NOW, 10, 0);
    svc.list(" active ", 2, 10);
    verify(repo).list("ACTIVE", NOW, 10, 20);
    assertThatCode(() -> svc.list("ended", 0, 10)).doesNotThrowAnyException();
    rejects(() -> svc.list("later", 0, 10), "VALIDATION_FAILED", "must be SCHEDULED");
  }

  // ---------- read (customers) ----------
  private FlashPricingService.ItemDef def(UUID sale, String channel, UUID itemId, UUID product, Integer max, long pct) {
    return new FlashPricingService.ItemDef(sale, "n", "b", "d", itemId, product, new FlashMath.Rule(FlashKind.PERCENT, pct),
        max, 2, NOW, NOW, channel);
  }

  private static Sale shopSale(UUID id, String channel, Instant start, Instant end) {
    return new Sale(id, "S-" + id.toString().substring(0, 4), "d", "b", start, end, channel, "PERCENT", 20, 0, null, ACTOR, PAST, PAST);
  }

  private static Product visible(UUID id, long price, String packSize, String unit) {
    return new Product(id, UUID.randomUUID(), "En", "Gu", "d", price, price, unit, packSize, "img", true, 5, false, true, true, id, 0, 0);
  }

  @Test
  void shopSalesListRunningAndComingSalesOnly() {
    UUID running = UUID.randomUUID(), pos = UUID.randomUUID(), empty = UUID.randomUUID(), coming = UUID.randomUUID(), ended = UUID.randomUUID();
    UUID i1 = UUID.randomUUID(), i2 = UUID.randomUUID(), i3 = UUID.randomUUID(), i4 = UUID.randomUUID(), i5 = UUID.randomUUID(), i6 = UUID.randomUUID();
    UUID pa = UUID.randomUUID(), pb = UUID.randomUUID(), pc = UUID.randomUUID(), pd = UUID.randomUUID(), pe = UUID.randomUUID(), pf = UUID.randomUUID();
    Instant soon = NOW.plus(Duration.ofMinutes(30));
    Sale sRun = shopSale(running, "ONLINE", PAST, LATER);
    Sale sPos = shopSale(pos, "POS", PAST, LATER);
    Sale sEmpty = shopSale(empty, "BOTH", PAST, LATER);
    Sale sComing = shopSale(coming, "BOTH", soon, LATER);
    Sale sEnded = shopSale(ended, "BOTH", PAST.minus(Duration.ofHours(1)), PAST);
    Map<UUID, List<FlashPricingService.ItemDef>> byProduct = new LinkedHashMap<>();
    byProduct.put(pa, List.of(def(running, "ONLINE", i1, pa, 10, 20)));
    byProduct.put(pb, List.of(def(running, "ONLINE", i2, pb, null, 20)));       // blank pack size -> unit
    byProduct.put(pc, List.of(def(running, "ONLINE", i3, pc, 4, 20)));          // null pack size -> unit, sold more than the cap
    byProduct.put(pd, List.of(def(running, "ONLINE", i4, pd, null, 20)));       // list price too low -> no sale price
    byProduct.put(pe, List.of(def(running, "ONLINE", i5, pe, null, 20)));       // product not visible
    byProduct.put(pf, List.of(def(empty, "BOTH", i6, pf, null, 20), def(coming, "BOTH", UUID.randomUUID(), pa, null, 10),
        def(pos, "POS", UUID.randomUUID(), pa, null, 10), def(ended, "BOTH", UUID.randomUUID(), pa, null, 10)));
    when(pricing.defs()).thenReturn(new FlashPricingService.Defs(byProduct, List.of(new SaleWithItems(sRun, List.of()),
        new SaleWithItems(sPos, List.of()), new SaleWithItems(sEmpty, List.of()), new SaleWithItems(sComing, List.of()),
        new SaleWithItems(sEnded, List.of()))));
    when(catalog.visible(anyList())).thenReturn(List.of(visible(pa, 10000, "500 g", "kg"), visible(pb, 20000, " ", "litre"),
        visible(pc, 30000, null, "box"), visible(pd, 100, "x", "y"), visible(pa, 10000, "dup", "dup")));
    when(repo.soldQtys(anyCollection())).thenReturn(Map.of(i1, 3, i3, 9));

    ShopSales active = svc.active();
    assertThat(active.now()).isEqualTo(NOW);
    assertThat(active.sales()).hasSize(1);
    var sale = active.sales().get(0);
    assertThat(sale.id()).isEqualTo(running);
    assertThat(sale.secondsToStart()).isZero();
    assertThat(sale.secondsToEnd()).isEqualTo(3600);
    assertThat(sale.items()).extracting(FlashViews.ShopItem::unitLabel).containsExactly("500 g", "litre", "box");
    assertThat(sale.items()).extracting(FlashViews.ShopItem::unitsLeft).containsExactly(7, null, 0);
    assertThat(sale.items().get(0).salePricePaise()).isEqualTo(8000);

    ShopSales upcoming = svc.upcoming();
    assertThat(upcoming.sales()).extracting(FlashViews.ShopSale::id).containsExactly(coming);
    assertThat(upcoming.sales().get(0).secondsToStart()).isEqualTo(1800);
  }

  @Test
  void shopSalesWithNothingToShow() {
    when(pricing.defs()).thenReturn(new FlashPricingService.Defs(Map.of(), List.of()));
    when(catalog.visible(anyList())).thenReturn(List.of());
    assertThat(svc.active().sales()).isEmpty();
    assertThat(svc.upcoming().sales()).isEmpty();
  }
}
