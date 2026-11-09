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
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.purchasing.app.PurchaseService.ItemInput;
import com.vijay.mall.purchasing.app.PurchaseService;
import com.vijay.mall.purchasing.app.StockTakeService;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PurchasingServicesTest {
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private final UUID actor = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();
  private final UUID dal = UUID.randomUUID();
  private PurchaseRepository repo;
  private InventoryService inventory;
  private CatalogService catalog;
  private AuditService audit;
  private PurchaseService purchases;
  private StockTakeService stockTake;

  @BeforeEach
  void setUp() {
    repo = mock(PurchaseRepository.class);
    inventory = mock(InventoryService.class);
    catalog = mock(CatalogService.class);
    audit = mock(AuditService.class);
    purchases = new PurchaseService(repo, inventory, catalog, audit, new TransactionTemplate(mock(PlatformTransactionManager.class)),
        Clock.fixed(NOW, ZoneOffset.UTC));
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

  private PurchaseService.Input input(ItemInput... items) {
    return new PurchaseService.Input(" Agro Traders ", "INV-77", "morning delivery", List.of(items));
  }

  private PurchaseRepository.Header header(UUID id) {
    return new PurchaseRepository.Header(id, "Agro Traders", "INV-77", "morning delivery", 100 * 50 + 20 * 80, actor, NOW);
  }

  private void stubStored(UUID id) {
    var header = header(id);
    when(repo.find(id)).thenReturn(Optional.of(header));
    when(repo.lines(id)).thenReturn(List.of(new PurchaseRepository.Line(rice, 100, 50), new PurchaseRepository.Line(dal, 20, 80)));
  }

  @Test
  void receivingAddsEachProductsStockLinkedToTheDocumentAndTotalsTheCost() {
    UUID id = UUID.randomUUID();
    when(repo.insert(any(), any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(id);
    stubStored(id);
    var v = purchases.receive(actor, "key-1", input(new ItemInput(rice, 100, 50), new ItemInput(dal, 20, 80)));

    verify(repo).insert(eq("Agro Traders"), eq("INV-77"), eq("morning delivery"), eq(6600L), eq("key-1"), eq(actor), any(), eq(NOW));
    verify(inventory).adjust(rice, 100, "PURCHASE", actor, id);
    verify(inventory).adjust(dal, 20, "PURCHASE", actor, id);
    verify(audit).log(eq(actor), eq("PURCHASE"), eq("purchase"), eq(id), anyString());
    assertThat(v.items()).extracting(PurchaseService.ItemView::name).containsExactly("Rice", "Dal");
    assertThat(v.items().get(0).lineCostPaise()).isEqualTo(5000);
    assertThat(v.totalCostPaise()).isEqualTo(6600);
  }

  private static long anyLong() { return ArgumentMatchers.anyLong(); }

  @Test
  void aRepeatedKeyReturnsTheFirstDeliveryWithoutAddingStockAgain() {
    UUID id = UUID.randomUUID();
    stubStored(id);
    when(repo.findByIdem("key-2")).thenReturn(Optional.of(header(id)));
    purchases.receive(actor, "key-2", input(new ItemInput(dal, 20, 80), new ItemInput(rice, 100, 50)));
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any(), any());
    verify(repo, never()).insert(any(), any(), any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void theSameKeyForADifferentDeliveryIsRefusedNotSwallowed() {
    UUID id = UUID.randomUUID();
    stubStored(id);
    when(repo.findByIdem("key-3")).thenReturn(Optional.of(header(id)));
    reused(() -> purchases.receive(actor, "key-3", input(new ItemInput(rice, 100, 51), new ItemInput(dal, 20, 80))), "IDEMPOTENCY_KEY_REUSED");
    reused(() -> purchases.receive(actor, "key-3", input(new ItemInput(rice, 100, 50))), "IDEMPOTENCY_KEY_REUSED");
    reused(() -> purchases.receive(actor, "key-3", new PurchaseService.Input("Other Supplier", "INV-77", null, List.of(new ItemInput(rice, 100, 50), new ItemInput(dal, 20, 80)))), "IDEMPOTENCY_KEY_REUSED");
    reused(() -> purchases.receive(actor, "key-3", new PurchaseService.Input("Agro Traders", null, null, List.of(new ItemInput(rice, 100, 50), new ItemInput(dal, 20, 80)))), "IDEMPOTENCY_KEY_REUSED");
    reused(() -> purchases.receive(actor, "key-3", new PurchaseService.Input("Agro Traders", "INV-77", null, null)), "IDEMPOTENCY_KEY_REUSED");
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any(), any());
  }

  @Test
  void twoRequestsAtTheSameMomentLeaveOneDelivery() {
    UUID id = UUID.randomUUID();
    stubStored(id);
    when(repo.findByIdem("race")).thenReturn(Optional.empty(), Optional.of(header(id)));
    when(repo.insert(any(), any(), any(), anyLong(), eq("race"), any(), any(), any())).thenThrow(new DataIntegrityViolationException("dup"));
    assertThat(purchases.receive(actor, "race", input(new ItemInput(rice, 100, 50), new ItemInput(dal, 20, 80))).id()).isEqualTo(id);
  }

  @Test
  void badInputIsRefusedBeforeAnyStockMoves() {
    ItemInput ok = new ItemInput(rice, 1, 10);
    assertCode(() -> purchases.receive(actor, null, input(ok)), "Idempotency-Key");
    assertCode(() -> purchases.receive(actor, " ", input(ok)), "Idempotency-Key");
    assertThatThrownBy(() -> purchases.receive(actor, "k", new PurchaseService.Input(" ", "", "", List.of(ok)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", new PurchaseService.Input(null, "", "", List.of(ok)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", new PurchaseService.Input("S".repeat(121), "", "", List.of(ok)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", new PurchaseService.Input("S", "I".repeat(61), "", List.of(ok)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", new PurchaseService.Input("S", "", "N".repeat(301), List.of(ok)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", new PurchaseService.Input("S", null, null, List.of()))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", new PurchaseService.Input("S", null, null, null))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", input(new ItemInput(null, 1, 1)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", input(ok, ok))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", input(new ItemInput(rice, 0, 1)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", input(new ItemInput(rice, PurchaseService.MAX_QTY + 1, 1)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", input(new ItemInput(rice, 1, -1)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", input(new ItemInput(rice, 1, PurchaseService.MAX_UNIT_COST + 1)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> purchases.receive(actor, "k", input(new ItemInput(UUID.randomUUID(), 1, 1)))).isInstanceOf(ApiException.class);
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any(), any());
  }

  private void reused(Runnable r, String code) {
    assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> Assertions.assertThat(e.code()).isEqualTo(code));
  }

  private void assertCode(Runnable r, String field) {
    assertThatThrownBy(r::run).isInstanceOf(ApiException.class).hasMessageContaining("required");
  }

  @Test
  void tooManyLinesAreRefused() {
    var many = IntStream.range(0, PurchaseService.MAX_LINES + 1).mapToObj(i -> new ItemInput(UUID.randomUUID(), 1, 1)).toList();
    assertThatThrownBy(() -> purchases.receive(actor, "k", new PurchaseService.Input("S", "", "", many))).isInstanceOf(ApiException.class);
  }

  @Test
  void getAndListShowNamesAndNotFoundIsA404() {
    UUID id = UUID.randomUUID();
    stubStored(id);
    assertThat(purchases.get(id).items()).hasSize(2);
    assertThatThrownBy(() -> purchases.get(UUID.randomUUID())).isInstanceOf(ApiException.class);
    when(repo.page(0, 20)).thenReturn(new Page<>(List.of(header(id)), 0, 20, 1));
    assertThat(purchases.list(-5, 20).items()).singleElement().satisfies(p -> assertThat(p.supplierName()).isEqualTo("Agro Traders"));
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
