package com.vijay.mall.inventory.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.inventory.domain.Lot;
import com.vijay.mall.inventory.infra.LotRepository;
import com.vijay.mall.inventory.infra.LotRepository.Allocation;
import com.vijay.mall.inventory.infra.LotRepository.ProductLots;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class LotServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private final UUID p = UUID.randomUUID();
  private final UUID ref1 = UUID.randomUUID();
  private final UUID ref2 = UUID.randomUUID();
  private LotRepository repo;
  private ApplicationEventPublisher events;
  private LotService service;

  @BeforeEach
  void setUp() {
    repo = mock(LotRepository.class);
    events = mock(ApplicationEventPublisher.class);
    service = new LotService(repo, Clock.fixed(NOW, ZoneOffset.UTC), events);
  }

  private Lot lot(int day, int qty, String cost, Long price, Long mrp) {
    return new Lot(UUID.randomUUID(), p, NOW.plusSeconds(day), 10, qty, new BigDecimal(cost), true, mrp, price, "", null, "PURCHASE");
  }

  private ProductLots product(boolean lotPriced, Lot... lots) {
    return new ProductLots(p, lotPriced, false, 15000, 16000, List.of(lots));
  }

  @Test
  void takeServesLinesInOrderAcrossLotsAndRollsThePriceWhenTheCurrentLotIsUsedUp() {
    Lot a = lot(0, 3, "100", 15000L, 16000L);
    Lot b = lot(1, 10, "120", 17000L, null);
    when(repo.lockOpen(any())).thenReturn(Map.of(p, product(true, a, b)));
    when(repo.setProductPrice(eq(p), anyLong(), anyLong(), any())).thenReturn(true);
    var costs = service.take(List.of(new LotService.Want(p, 2, ref1), new LotService.Want(p, 3, ref2), new LotService.Want(p, 0, UUID.randomUUID())), LotService.POS_SALE_ITEM);
    assertThat(costs).containsEntry(ref1, 200L).containsEntry(ref2, 100L + 240L);
    ArgumentCaptor<Map<UUID, Integer>> dec = ArgumentCaptor.captor();
    verify(repo).decrement(dec.capture());
    assertThat(dec.getValue()).containsEntry(a.id(), 3).containsEntry(b.id(), 2);
    verify(repo).setProductPrice(p, 17000, 16000, NOW);
    verify(events).publishEvent(any(CatalogChanged.class));
  }

  @Test
  void takeOpensAnUnknownCostLotForStockThatWasNeverInALotAndSkipsPricesOfPlainProducts() {
    when(repo.lockOpen(any())).thenReturn(Map.of());
    var costs = service.take(List.of(new LotService.Want(p, 2, ref1)), LotService.ORDER_ITEM);
    assertThat(costs).containsEntry(ref1, 0L);
    ArgumentCaptor<Lot> repair = ArgumentCaptor.captor();
    verify(repo).insertLot(repair.capture(), eq(null), any());
    assertThat(repair.getValue().costKnown()).isFalse();
    assertThat(service.take(List.of(), "X")).isEmpty();
    Lot a = lot(0, 3, "1", null, null);
    when(repo.lockOpen(any())).thenReturn(Map.of(p, product(true, a)));
    service.take(List.of(new LotService.Want(p, 1, ref1)), LotService.ORDER_ITEM);
    Lot plain = lot(0, 3, "1", 5L, null);
    when(repo.lockOpen(any())).thenReturn(Map.of(p, product(false, plain)));
    service.take(List.of(new LotService.Want(p, 3, ref1)), LotService.ORDER_ITEM);
    verify(repo, never()).setProductPrice(any(), anyLong(), anyLong(), any());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void aPurchaseReturnTakesTheReturnedPurchasesLotsFirst() {
    Lot mine = lot(5, 2, "50", null, null);
    Lot old = lot(0, 5, "10", null, null);
    when(repo.lockOpen(any())).thenReturn(Map.of(p, product(false, old, mine)));
    when(repo.purchaseLots(any(), eq(p))).thenReturn(List.of(mine));
    long cost = service.takeForPurchaseReturn(p, 3, UUID.randomUUID(), ref1);
    assertThat(cost).isEqualTo(2 * 50 + 10);
    when(repo.lockOpen(any())).thenReturn(Map.of());
    when(repo.purchaseLots(any(), eq(p))).thenReturn(List.of());
    assertThat(service.takeForPurchaseReturn(p, 1, UUID.randomUUID(), ref1)).isZero();
    when(repo.lockOpen(any())).thenReturn(Map.of(p, product(true, old)));
    when(repo.purchaseLots(any(), eq(p))).thenReturn(List.of());
    service.takeForPurchaseReturn(p, 1, UUID.randomUUID(), ref1);
  }

  @Test
  void giveBackReturnsUnitsToTheirLotsAndOpensAReturnLotForOldSales() {
    UUID lotId = UUID.randomUUID();
    Allocation alloc = new Allocation(UUID.randomUUID(), lotId, p, "ORDER_ITEM", ref1, 3, new BigDecimal("33.3333"), 100, 1, 33);
    Allocation other = new Allocation(UUID.randomUUID(), lotId, UUID.randomUUID(), "ORDER_ITEM", ref1, 3, BigDecimal.ONE, 3, 0, 0);
    Allocation full = new Allocation(UUID.randomUUID(), lotId, p, "ORDER_ITEM", ref1, 3, BigDecimal.ONE, 3, 3, 3);
    when(repo.openAllocations(eq("ORDER_ITEM"), any())).thenReturn(List.of(other, full, alloc));
    when(repo.lastKnownCost(any())).thenReturn(Map.of(p, new BigDecimal("7")));
    when(repo.lockOpen(any())).thenReturn(Map.of());
    var costs = service.giveBack(List.of(new LotService.Want(p, 3, ref1)), "ORDER_ITEM");
    assertThat(costs).containsEntry(ref1, 67L);
    ArgumentCaptor<List<LotRepository.Back>> backs = ArgumentCaptor.captor();
    verify(repo).putBack(backs.capture(), any());
    assertThat(backs.getValue()).singleElement().satisfies(b -> assertThat(b.qty()).isEqualTo(2));
    ArgumentCaptor<Lot> ret = ArgumentCaptor.captor();
    verify(repo).insertLot(ret.capture(), eq(null), any());
    assertThat(ret.getValue().source()).isEqualTo("RETURN");
    assertThat(ret.getValue().qtyReceived()).isEqualTo(1);
    assertThat(service.giveBack(List.of(), "X")).isEmpty();
    when(repo.lastKnownCost(any())).thenReturn(Map.of());
    service.giveBack(List.of(new LotService.Want(p, 1, null)), "ORDER_ITEM");
  }

  @Test
  void addLotUsesTheLastKnownCostWhenNoneIsGivenAndLetsPricesFollow() {
    when(repo.lastKnownCost(any())).thenReturn(Map.of(p, new BigDecimal("9")), Map.of());
    Lot a = lot(0, 3, "1", 100L, null);
    when(repo.lockOpen(any())).thenReturn(Map.of(p, product(true, a)));
    service.addLot(new LotService.NewLot(p, 2, null, null, null, null, null, null, null, null, "ADJUSTMENT"));
    service.addLot(new LotService.NewLot(p, 2, null, NOW, null, null, null, null, "B", null, "ADJUSTMENT"));
    service.addLot(new LotService.NewLot(p, 2, null, NOW, new BigDecimal("4"), false, 10L, 9L, "B", null, "PURCHASE"));
    ArgumentCaptor<Lot> lots = ArgumentCaptor.captor();
    verify(repo, org.mockito.Mockito.times(3)).insertLot(lots.capture(), any(), any());
    assertThat(lots.getAllValues()).extracting(Lot::unitCost).containsExactly(new BigDecimal("9"), BigDecimal.ZERO, new BigDecimal("4"));
    assertThat(lots.getAllValues()).extracting(Lot::costKnown).containsExactly(true, false, false);
  }

  @Test
  void writeOffAndInfoAndPricedLots() {
    Lot a = lot(0, 4, "2.5", 100L, 120L);
    service.writeOff(a);
    verify(repo).decrement(Map.of(a.id(), 4));
    when(repo.lockLot(a.id())).thenReturn(Optional.of(a));
    assertThat(service.lockLot(a.id())).contains(a);
    when(repo.openLots(p)).thenReturn(product(true, a));
    assertThat(service.info(p, 100, 120)).isPresent();
    when(repo.openLotPriced(any())).thenReturn(Map.of(p, product(true, a)));
    assertThat(service.pricedLots(List.of(p))).containsKey(p);
    LotService noEvents = new LotService(repo, Clock.fixed(NOW, ZoneOffset.UTC), null);
    when(repo.lockOpen(any())).thenReturn(Map.of(p, product(true, lot(0, 1, "1", 1L, null))));
    when(repo.setProductPrice(eq(p), anyLong(), anyLong(), any())).thenReturn(true);
    noEvents.writeOff(a);
  }
}
