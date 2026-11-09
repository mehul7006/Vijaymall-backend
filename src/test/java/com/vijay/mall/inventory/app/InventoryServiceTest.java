package com.vijay.mall.inventory.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.inventory.app.InventoryService.Item;
import com.vijay.mall.inventory.infra.InventoryRepository.Stock;
import com.vijay.mall.inventory.infra.InventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;

class InventoryServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final UUID A = new UUID(1, 0);
  private static final UUID B = new UUID(2, 0);
  private static final UUID C = new UUID(3, 0);
  private final UUID order = UUID.randomUUID();
  private InventoryRepository repo;
  private InventoryService service;

  @BeforeEach
  void setUp() {
    repo = mock(InventoryRepository.class);
    service = new InventoryService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void reserveTakesItemsInProductIdOrderAndRecordsAnOrderMovementForEach() {
    when(repo.reserve(any(), anyInt(), any())).thenReturn(1);

    service.reserve(List.of(new Item(C, 1), new Item(A, 2), new Item(B, 3)), order);

    InOrder in = inOrder(repo);
    in.verify(repo).reserve(A, 2, NOW);
    in.verify(repo).movement(A, -2, "ORDER", order, null, NOW);
    in.verify(repo).reserve(B, 3, NOW);
    in.verify(repo).movement(B, -3, "ORDER", order, null, NOW);
    in.verify(repo).reserve(C, 1, NOW);
    in.verify(repo).movement(C, -1, "ORDER", order, null, NOW);
  }

  @Test
  void reserveReportsEveryShortLineWithWhatIsAvailableAndRecordsNoMovementForThem() {
    when(repo.reserve(eq(A), anyInt(), any())).thenReturn(1);
    when(repo.reserve(eq(B), anyInt(), any())).thenReturn(0);
    when(repo.reserve(eq(C), anyInt(), any())).thenReturn(0);
    when(repo.quantity(B)).thenReturn(2);
    when(repo.quantity(C)).thenReturn(0);

    assertThatThrownBy(() -> service.reserve(List.of(new Item(A, 1), new Item(B, 5), new Item(C, 1)), order))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status().value()).isEqualTo(409);
          assertThat(e.code()).isEqualTo("OUT_OF_STOCK");
          assertThat((List<Map<String,Object>>) e.details().get("items")).containsExactly(
              Map.of("productId", B.toString(), "available", 2),
              Map.of("productId", C.toString(), "available", 0));
        });
    verify(repo).movement(A, -1, "ORDER", order, null, NOW); // the caller's transaction rolls this back
    verify(repo, never()).movement(eq(B), anyInt(), any(), any(), any(), any());
    verify(repo, never()).movement(eq(C), anyInt(), any(), any(), any(), any());
  }

  @Test
  void reserveOfNothingDoesNothing() {
    service.reserve(List.of(), order);
    verify(repo, never()).reserve(any(), anyInt(), any());
  }

  @Test
  void releaseGivesStockBackAndRecordsPositiveCancelMovements() {
    service.release(List.of(new Item(A, 2), new Item(B, 1)), order);
    verify(repo).add(A, 2, NOW);
    verify(repo).movement(A, 2, "CANCEL", order, null, NOW);
    verify(repo).add(B, 1, NOW);
    verify(repo).movement(B, 1, "CANCEL", order, null, NOW);
  }

  @Test
  void adjustRecordsMovementWithActorAndReturnsNewQuantity() {
    UUID actor = UUID.randomUUID();
    when(repo.add(A, -4, NOW)).thenReturn(1);
    when(repo.quantity(A)).thenReturn(6);
    assertThat(service.adjust(A, -4, "ADJUST", actor)).isEqualTo(6);
    verify(repo).movement(A, -4, "ADJUST", null, actor, NOW);
  }

  @Test
  void adjustBelowZeroIsAValidationErrorWithNoMovement() {
    when(repo.add(A, -9, NOW)).thenReturn(0);
    assertThatThrownBy(() -> service.adjust(A, -9, "ADJUST", UUID.randomUUID()))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status().value()).isEqualTo(400);
          assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
          assertThat(e.details().toString()).contains("delta", "stock cannot go below zero");
        });
    verify(repo, never()).movement(any(), anyInt(), any(), any(), any(), any());
  }

  @Test
  void stocksFillsMissingProductsWithEmptyStockAndKeepsRequestOrderWithoutDuplicates() {
    when(repo.stocks(any())).thenReturn(List.of(new Stock(A, 7, 2)));

    Map<UUID, Stock> out = service.stocks(List.of(B, A, B));

    assertThat(out.keySet()).containsExactly(B, A);
    assertThat(out.get(A)).isEqualTo(new Stock(A, 7, 2));
    assertThat(out.get(B)).isEqualTo(new Stock(B, 0, 5));
  }

  @Test
  void stocksOfNoProductsIsEmpty() {
    assertThat(service.stocks(List.of())).isEmpty();
  }

  @Test
  void createThresholdQuantityAndLowStockDelegate() {
    service.create(A, 10, 3);
    verify(repo).create(A, 10, 3, NOW);
    service.setLowThreshold(A, 4);
    verify(repo).setThreshold(A, 4);
    when(repo.quantity(A)).thenReturn(9);
    assertThat(service.quantity(A)).isEqualTo(9);
    when(repo.quantityForUpdate(A)).thenReturn(8);
    assertThat(service.quantityForUpdate(A)).isEqualTo(8);
    when(repo.lowStock(5)).thenReturn(List.of(new Stock(A, 1, 5)));
    assertThat(service.lowStock(5)).containsExactly(new Stock(A, 1, 5));
    verify(repo, never()).movement(any(), anyInt(), any(), isNull(), isNull(), any());
  }

  @Test
  void movementsAreClampedAndPassedToTheRepository() {
    var page = new Page<InventoryRepository.Movement>(List.of(), 0, 50, 0);
    when(repo.movementPage(A, 0, 50)).thenReturn(page);
    assertThat(service.movements(A, -4, 500)).isSameAs(page);

    var all = new Page<InventoryRepository.Movement>(List.of(), 2, 20, 0);
    when(repo.movementPage(null, 2, 20)).thenReturn(all);
    assertThat(service.movements(null, 2, 20)).isSameAs(all);
  }

  @Test
  void checkAvailableLooksButNeverTakes() {
    when(repo.stocks(any())).thenReturn(List.of(new InventoryRepository.Stock(A, 5, 2), new InventoryRepository.Stock(B, 1, 2)));
    service.checkAvailable(List.of(new Item(A, 2), new Item(A, 3), new Item(B, 1))); // A: 5 wanted of 5, B: 1 of 1
    Mockito.verify(repo, Mockito.never()).reserve(any(), anyInt(), any());
  }

  @Test
  void checkAvailableNamesEveryShortLine() {
    when(repo.stocks(any())).thenReturn(List.of(new InventoryRepository.Stock(A, 5, 2)));
    var e = Assertions.assertThrows(ApiException.class,
        () -> service.checkAvailable(List.of(new Item(A, 2), new Item(A, 4), new Item(B, 1))));
    assertThat(e.code()).isEqualTo("OUT_OF_STOCK");
    assertThat(e.details().get("items").toString()).contains(A.toString()).contains(B.toString());
  }

  // ---------- low-stock alerts ----------
  private OutboxService alerting() {
    var outbox = mock(OutboxService.class);
    service.setOutbox(outbox);
    when(repo.reserve(any(), anyInt(), any())).thenReturn(1);
    return outbox;
  }

  @Test
  void fallingOntoTheAlertLevelRaisesOneAlert() {
    var outbox = alerting();
    when(repo.stocks(any())).thenReturn(List.of(new InventoryRepository.Stock(A, 5, 5))); // 7 -> 5 : at the level
    service.reserve(List.of(new Item(A, 2)), order);
    Mockito.verify(outbox).add(ArgumentMatchers.eq("LOW_STOCK"), any());
  }

  @Test
  void stayingLowDoesNotRepeatTheAlert() {
    var outbox = alerting();
    when(repo.stocks(any())).thenReturn(List.of(new InventoryRepository.Stock(A, 3, 5))); // 4 -> 3 : was already low
    service.reserve(List.of(new Item(A, 1)), order);
    Mockito.verify(outbox, Mockito.never()).add(any(), any());
  }

  @Test
  void runningOutAlertsEvenWhenTheLevelIsZero() {
    var outbox = alerting();
    when(repo.stocks(any())).thenReturn(List.of(new InventoryRepository.Stock(A, 0, 0))); // 2 -> 0, level 0
    service.reserve(List.of(new Item(A, 2)), order);
    Mockito.verify(outbox).add(ArgumentMatchers.eq("LOW_STOCK"), any());
  }

  @Test
  void aHighStockSaleRaisesNothingAndOnlyCrossingLinesAreListed() {
    var outbox = alerting();
    when(repo.stocks(any())).thenReturn(List.of(new InventoryRepository.Stock(A, 50, 5), new InventoryRepository.Stock(B, 4, 5)));
    service.reserve(List.of(new Item(A, 1), new Item(B, 2)), order); // A 51->50 fine, B 6->4 crosses
    var captured = ArgumentCaptor.forClass(Object.class);
    Mockito.verify(outbox).add(ArgumentMatchers.eq("LOW_STOCK"), captured.capture());
    assertThat(captured.getValue().toString()).contains(B.toString()).doesNotContain(A.toString());
  }

  @Test
  void anAdminWriteDownAlertsButRestockingDoesNot() {
    var outbox = alerting();
    when(repo.add(any(), anyInt(), any())).thenReturn(1);
    when(repo.stocks(any())).thenReturn(List.of(new InventoryRepository.Stock(A, 2, 5)));
    service.adjust(A, -4, "DAMAGED", UUID.randomUUID()); // 6 -> 2
    Mockito.verify(outbox).add(ArgumentMatchers.eq("LOW_STOCK"), any());
    Mockito.clearInvocations(outbox);
    service.adjust(A, 20, "RESTOCK", UUID.randomUUID());
    Mockito.verify(outbox, Mockito.never()).add(any(), any());
  }
}
