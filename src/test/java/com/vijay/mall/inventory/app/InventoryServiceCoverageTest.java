package com.vijay.mall.inventory.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.live.StockChanged;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.inventory.app.InventoryService.Item;
import com.vijay.mall.inventory.infra.InventoryRepository;
import com.vijay.mall.inventory.infra.InventoryRepository.Stock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** Alert and live-event branches of InventoryService with an outbox and a publisher wired in. */
class InventoryServiceCoverageTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private final UUID a = new UUID(1, 0);
  private InventoryRepository repo;
  private OutboxService outbox;
  private ApplicationEventPublisher events;
  private InventoryService service;

  @BeforeEach
  void setUp() {
    repo = mock(InventoryRepository.class);
    outbox = mock(OutboxService.class);
    events = mock(ApplicationEventPublisher.class);
    service = new InventoryService(repo, Clock.fixed(NOW, ZoneOffset.UTC), events);
    service.setOutbox(outbox);
    when(repo.reserve(any(), anyInt(), any())).thenReturn(1);
    when(repo.add(any(), anyInt(), any())).thenReturn(1);
  }

  @Test void reservingNothing_raisesNoAlertAndNoLiveEvent() {
    service.reserve(List.of(), UUID.randomUUID());
    verify(outbox, never()).add(anyString(), anyMap());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test void releasingNothing_sendsNoLiveEvent() {
    service.release(List.of(), UUID.randomUUID());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test void releasing_publishesAStockChangedEvent() {
    service.release(List.of(new Item(a, 2), new Item(a, 3)), UUID.randomUUID(), "RETURN");
    ArgumentCaptor<StockChanged> ev = ArgumentCaptor.forClass(StockChanged.class);
    verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue().reason()).isEqualTo("RETURN");
    assertThat(ev.getValue().productIds()).containsExactly(a);
  }

  @Test void sellingFromStockThatWasAlreadyEmpty_raisesNoSecondAlert() {
    // 0 on hand after taking 0 more: it was already empty before this sale, so staff were told earlier
    when(repo.stocks(any())).thenReturn(List.of(new Stock(a, 0, 5)));
    service.reserve(List.of(new Item(a, 0)), UUID.randomUUID());
    verify(outbox, never()).add(anyString(), anyMap());
  }

  @Test void stockStillAboveItsAlertLevel_raisesNothing() {
    when(repo.stocks(any())).thenReturn(List.of(new Stock(a, 50, 5)));
    service.reserve(List.of(new Item(a, 1)), UUID.randomUUID());
    verify(outbox, never()).add(anyString(), anyMap());
  }

  @Test void fallingOntoTheAlertLevel_raisesOneLowStockAlert() {
    when(repo.stocks(any())).thenReturn(List.of(new Stock(a, 5, 5)));
    service.reserve(List.of(new Item(a, 2)), UUID.randomUUID());
    verify(outbox).add(eq("LOW_STOCK"), eq(Map.of("items", List.of(Map.of("productId", a.toString(), "qty", 5, "low", 5)))));
  }

  @Test void stocksWithoutARowCountAsEmptyWithDefaultLevel_andRepeatedIdsAreMerged() {
    var found = service.stocks(List.of(a, a));
    assertThat(found).hasSize(1);
    assertThat(found.get(a)).isEqualTo(new Stock(a, 0, 5));
  }
}
