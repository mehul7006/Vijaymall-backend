package com.vijay.mall.inventory.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.inventory.app.InventoryService.Item;
import com.vijay.mall.inventory.infra.InventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TakeWhatIsLeftTest {
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private final UUID a = new UUID(1, 0);
  private final UUID b = new UUID(2, 0);
  private final UUID sale = UUID.randomUUID();
  private InventoryRepository repo;
  private InventoryService service;

  @BeforeEach
  void setUp() {
    repo = mock(InventoryRepository.class);
    service = new InventoryService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    when(repo.reserve(any(), anyInt(), any())).thenReturn(1);
  }

  @Test
  void whenEverythingIsThereItIsTakenAndNothingIsShort() {
    when(repo.quantity(a)).thenReturn(10);
    assertThat(service.takeWhatIsLeft(List.of(new Item(a, 3), new Item(a, 2)), sale, "POS_SALE")).isEmpty();
    verify(repo).reserve(a, 5, NOW);
    verify(repo).movement(a, -5, "POS_SALE", sale, null, NOW);
  }

  @Test
  void aShortLineTakesWhatIsLeftAndReportsTheRest() {
    when(repo.quantity(a)).thenReturn(1);
    var short_ = service.takeWhatIsLeft(List.of(new Item(a, 3)), sale, "POS_SALE");
    verify(repo).reserve(a, 1, NOW);
    assertThat(short_).singleElement().satisfies(s -> {
      assertThat(s.get("sold")).isEqualTo(3);
      assertThat(s.get("taken")).isEqualTo(1);
      assertThat(s.get("productId")).isEqualTo(a.toString());
    });
  }

  @Test
  void anEmptyShelfTakesNothingAndIsReported() {
    when(repo.quantity(b)).thenReturn(0);
    var short_ = service.takeWhatIsLeft(List.of(new Item(b, 2)), sale, "POS_SALE");
    verify(repo, never()).reserve(eq(b), anyInt(), any());
    verify(repo, never()).movement(any(), anyInt(), any(), any(), any(), any());
    assertThat(short_).singleElement().satisfies(s -> assertThat(s.get("taken")).isEqualTo(0));
  }

  @Test
  void aTakeThatLosesARaceIsReportedAsShortNotThrown() {
    when(repo.quantity(a)).thenReturn(4);
    when(repo.reserve(any(), anyInt(), any())).thenReturn(0); // someone else emptied the shelf in between
    var short_ = service.takeWhatIsLeft(List.of(new Item(a, 2)), sale, "POS_SALE");
    assertThat(short_).hasSize(1);
    verify(repo, never()).movement(any(), anyInt(), any(), any(), any(), any());
  }
}
