package com.vijay.mall.inventory.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService.Item;
import com.vijay.mall.inventory.domain.Lot;
import com.vijay.mall.inventory.infra.InventoryRepository;
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

/** Every stock movement of the inventory service is routed through the lots. */
class InventoryLotsRoutingTest {
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private final UUID a = new UUID(1, 0);
  private final UUID b = new UUID(2, 0);
  private final UUID ref = UUID.randomUUID();
  private InventoryRepository repo;
  private LotService lots;
  private InventoryService service;

  @BeforeEach
  void setUp() {
    repo = mock(InventoryRepository.class);
    lots = mock(LotService.class);
    service = new InventoryService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    service.setLots(lots);
    when(repo.reserve(any(), anyInt(), any())).thenReturn(1);
    when(repo.add(any(), anyInt(), any())).thenReturn(1);
  }

  @Test
  void reserveTakesLotsInProductOrderAndReturnsTheCosts() {
    when(lots.take(anyList(), eq("POS_SALE_ITEM"))).thenReturn(Map.of(ref, 500L));
    var costs = service.reserve(List.of(new Item(b, 1, ref), new Item(a, 2)), UUID.randomUUID(), "POS_SALE");
    assertThat(costs).containsEntry(ref, 500L);
    ArgumentCaptor<List<LotService.Want>> wants = ArgumentCaptor.captor();
    verify(lots).take(wants.capture(), eq("POS_SALE_ITEM"));
    assertThat(wants.getValue()).extracting(LotService.Want::productId).containsExactly(a, b);
  }

  @Test
  void orderReservesUseOrderItemRefsAndOtherReasonsAdjustmentRefs() {
    service.reserve(List.of(new Item(a, 1, ref)), UUID.randomUUID());
    verify(lots).take(anyList(), eq("ORDER_ITEM"));
    service.reserve(List.of(new Item(a, 1, ref)), UUID.randomUUID(), "SOMETHING");
    verify(lots).take(anyList(), eq("ADJUSTMENT"));
  }

  @Test
  void aFailedReserveTouchesNoLot() {
    when(repo.reserve(any(), anyInt(), any())).thenReturn(0);
    assertThatThrownBy(() -> service.reserve(List.of(new Item(a, 1)), ref)).isInstanceOf(ApiException.class);
    verify(lots, never()).take(anyList(), any());
  }

  @Test
  void offlineBillsAreCostedAndSharedOverTheirLines() {
    when(repo.quantity(a)).thenReturn(3);
    when(lots.take(anyList(), eq("POS_SALE_ITEM"))).thenReturn(Map.of(ref, 9L));
    var got = service.takeWhatIsLeftCosted(List.of(new Item(a, 2, ref), new Item(a, 4, UUID.randomUUID()), new Item(b, 1)), ref, "POS_SALE");
    assertThat(got.costs()).containsEntry(ref, 9L);
    assertThat(got.shortfalls()).hasSize(2);
    ArgumentCaptor<List<LotService.Want>> wants = ArgumentCaptor.captor();
    verify(lots).take(wants.capture(), eq("POS_SALE_ITEM"));
    assertThat(wants.getValue()).extracting(LotService.Want::qty).containsExactly(2, 1);
  }

  @Test
  void releaseGivesBackToTheirLotsOrOpensAReturnLot() {
    service.release(List.of(new Item(a, 2, ref)), UUID.randomUUID(), "CANCEL");
    verify(lots).giveBack(anyList(), eq("ORDER_ITEM"));
    service.release(List.of(new Item(a, 2, ref)), UUID.randomUUID(), "POS_RETURN");
    verify(lots).giveBack(anyList(), eq("POS_SALE_ITEM"));
    service.release(List.of(new Item(a, 2)), UUID.randomUUID(), "OTHER");
    ArgumentCaptor<LotService.NewLot> lot = ArgumentCaptor.captor();
    verify(lots).addLot(lot.capture());
    assertThat(lot.getValue().source()).isEqualTo("RETURN");
  }

  @Test
  void adjustAddsLotsForPlusAndConsumesForMinus() {
    service.adjust(a, 5, "RESTOCK", null);
    service.adjust(a, 3, "DEFECTIVE_REVIEW_RESTOCK", null, ref);
    ArgumentCaptor<LotService.NewLot> lot = ArgumentCaptor.captor();
    verify(lots, org.mockito.Mockito.times(2)).addLot(lot.capture());
    assertThat(lot.getAllValues()).extracting(LotService.NewLot::source).containsExactly("ADJUSTMENT", "RETURN");
    service.adjust(a, -2, "ADJUST", null);
    service.adjust(a, -2, "ADJUST", null, ref);
    verify(lots, org.mockito.Mockito.times(2)).take(anyList(), eq("ADJUSTMENT"));
  }

  @Test
  void createReconcilesLotsWithTheNewQuantity() {
    when(repo.quantity(a)).thenReturn(0, 10, 10, 4);
    service.create(a, 10, 5);
    ArgumentCaptor<LotService.NewLot> lot = ArgumentCaptor.captor();
    verify(lots).addLot(lot.capture());
    assertThat(lot.getValue().source()).isEqualTo("OPENING");
    service.create(a, 10, 5);
    service.create(a, 12, 5);
    verify(lots, org.mockito.Mockito.times(2)).addLot(any());
    service.create(a, 1, 5);
    verify(lots).take(anyList(), eq("ADJUSTMENT"));
  }

  @Test
  void receiveOpensALotAndReturnToSupplierConsumesPurchaseLotsFirst() {
    UUID purchase = UUID.randomUUID();
    service.receive(new InventoryService.Receipt(a, 4, ref, NOW, new BigDecimal("12.5"), 100L, 90L, "B1", null), purchase, null);
    verify(repo).movement(a, 4, "PURCHASE", purchase, null, NOW);
    verify(lots).addLot(any());
    service.returnToSupplier(a, 2, purchase, ref, null);
    verify(lots).takeForPurchaseReturn(a, 2, purchase, ref);
    when(repo.add(any(), eq(-9), any())).thenReturn(0);
    assertThatThrownBy(() -> service.returnToSupplier(a, 9, purchase, ref, null)).isInstanceOf(ApiException.class);
  }

  @Test
  void writeOffEmptiesTheLotAndGuardsAgainstDriftAndEmptyLots() {
    UUID lotId = UUID.randomUUID();
    Lot lot = new Lot(lotId, a, NOW, 10, 4, BigDecimal.ONE, true, null, null, "", null, "PURCHASE");
    when(lots.lockLot(lotId)).thenReturn(Optional.of(lot));
    assertThat(service.writeOffLot(lotId, null)).isEqualTo(4);
    verify(lots).writeOff(lot);
    verify(repo).movement(a, -4, "EXPIRY_WRITE_OFF", lotId, null, NOW);
    when(lots.lockLot(lotId)).thenReturn(Optional.of(lot.withRemaining(0)));
    assertThatThrownBy(() -> service.writeOffLot(lotId, null)).isInstanceOf(ApiException.class);
    when(lots.lockLot(lotId)).thenReturn(Optional.of(lot));
    when(repo.add(any(), eq(-4), any())).thenReturn(0);
    assertThatThrownBy(() -> service.writeOffLot(lotId, null)).isInstanceOf(ApiException.class);
    when(lots.lockLot(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.writeOffLot(UUID.randomUUID(), null)).isInstanceOf(ApiException.class);
    InventoryService plain = new InventoryService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> plain.writeOffLot(lotId, null)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void lotQueriesAreEmptyWithoutLotsAndCapOnlyMixedPrices() {
    InventoryService plain = new InventoryService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(plain.lotInfo(a, 1, 1)).isEmpty();
    assertThat(plain.pricedLots(List.of(a))).isEmpty();
    assertThat(plain.reserve(List.of(new Item(a, 1)), ref)).isEmpty();
    Lot l1 = new Lot(UUID.randomUUID(), a, NOW, 5, 3, BigDecimal.ONE, true, 120L, 100L, "", null, "PURCHASE");
    Lot l2 = new Lot(UUID.randomUUID(), b, NOW, 5, 3, BigDecimal.ONE, true, 120L, 100L, "", null, "PURCHASE");
    Lot l3 = new Lot(UUID.randomUUID(), b, NOW, 5, 3, BigDecimal.ONE, true, 140L, 130L, "", null, "PURCHASE");
    when(lots.pricedLots(any())).thenReturn(Map.of(a, List.of(l1), b, List.of(l2, l3)));
    assertThat(service.unitsAtThisPrice(List.of(a, b))).containsOnlyKeys(b).containsEntry(b, 3);
    service.lotInfo(a, 1, 1);
    verify(lots).info(a, 1, 1);
  }
}
