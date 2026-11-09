package com.vijay.mall.inventory.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.inventory.infra.InventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** The shop list changes when a product sells out or comes back in, and only then: that is the moment remembered searches must go. */
class StockAvailabilityEventTest {
  private final UUID product = UUID.randomUUID();
  private InventoryRepository repo;
  private ApplicationEventPublisher events;
  private InventoryService service;

  @BeforeEach
  void setUp() {
    repo = mock(InventoryRepository.class);
    events = mock(ApplicationEventPublisher.class);
    service = new InventoryService(repo, Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC), events);
    when(repo.reserve(any(), anyInt(), any())).thenReturn(1);
    when(repo.add(any(), anyInt(), any())).thenReturn(1);
  }

  private void catalogEvents(int times) {
    verify(events, times(times)).publishEvent(argThat((Object e) -> e instanceof CatalogChanged c && CatalogChanged.CATALOG.equals(c.area())));
  }

  @Test
  void sellingTheLastUnitAnnouncesThatTheProductLeftTheShopList() {
    when(repo.quantity(product)).thenReturn(0); // 3 were there, 3 taken
    service.reserve(List.of(new InventoryService.Item(product, 3)), UUID.randomUUID());
    catalogEvents(1);
  }

  @Test
  void sellingSomeButNotAllAnnouncesNothing() {
    when(repo.quantity(product)).thenReturn(4); // 7 were there, 3 taken
    service.reserve(List.of(new InventoryService.Item(product, 3)), UUID.randomUUID());
    catalogEvents(0);
  }

  @Test
  void aReturnOrARestockOfAProductThatHadNoneAnnouncesItIsBack() {
    when(repo.quantity(product)).thenReturn(2); // was 0
    service.release(List.of(new InventoryService.Item(product, 2)), UUID.randomUUID());
    catalogEvents(1);
    when(repo.quantity(product)).thenReturn(5); // was 3
    service.release(List.of(new InventoryService.Item(product, 2)), UUID.randomUUID());
    catalogEvents(1);
  }

  @Test
  void anAdjustmentThatCrossesZeroEitherWayAnnouncesIt() {
    when(repo.quantity(product)).thenReturn(50); // 0 -> 50
    service.adjust(product, 50, "RESTOCK", UUID.randomUUID());
    catalogEvents(1);
    when(repo.quantity(product)).thenReturn(0); // 5 -> 0
    service.adjust(product, -5, "ADJUST", UUID.randomUUID());
    catalogEvents(2);
    when(repo.quantity(product)).thenReturn(6); // 10 -> 6
    service.adjust(product, -4, "ADJUST", UUID.randomUUID());
    catalogEvents(2);
  }

  @Test
  void takingWhatIsLeftForAnOfflineBillAnnouncesASellOutToo() {
    when(repo.quantity(product)).thenReturn(0);
    when(repo.reserve(any(), anyInt(), any())).thenReturn(1);
    service.takeWhatIsLeft(List.of(new InventoryService.Item(product, 2)), UUID.randomUUID(), "POS_SALE");
    verify(events, never()).publishEvent(argThat((Object e) -> e instanceof RuntimeException));
  }

  @Test
  void withoutAPublisherNothingBreaks() {
    var quiet = new InventoryService(repo, Clock.systemUTC());
    quiet.release(List.of(new InventoryService.Item(product, 2)), UUID.randomUUID());
  }
}
