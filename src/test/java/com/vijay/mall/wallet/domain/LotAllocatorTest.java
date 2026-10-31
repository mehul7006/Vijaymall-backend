package com.vijay.mall.wallet.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LotAllocatorTest {
  private static final Instant T0 = Instant.parse("2027-01-01T00:00:00Z");

  @Test
  void spendsTheLotThatExpiresFirst() {
    var soon = new LotAllocator.Lot(UUID.randomUUID(), 10, T0);
    var later = new LotAllocator.Lot(UUID.randomUUID(), 50, T0.plusSeconds(86400));
    var uses = LotAllocator.allocate(List.of(later, soon), 15);
    assertEquals(2, uses.size());
    assertEquals(soon.id(), uses.get(0).lotId());
    assertEquals(10, uses.get(0).coins());
    assertEquals(5, uses.get(1).coins());
  }

  @Test
  void lotsWithoutExpiryAreSpentLast() {
    var never = new LotAllocator.Lot(UUID.randomUUID(), 10, null);
    var dated = new LotAllocator.Lot(UUID.randomUUID(), 10, T0);
    assertEquals(dated.id(), LotAllocator.allocate(List.of(never, dated), 5).get(0).lotId());
  }

  @Test
  void failsWhenNotEnoughCoins() {
    var lot = new LotAllocator.Lot(UUID.randomUUID(), 3, T0);
    assertThrows(IllegalStateException.class, () -> LotAllocator.allocate(List.of(lot), 4));
  }

  @Test
  void zeroCoinsUsesNothing() {
    assertEquals(0, LotAllocator.allocate(List.of(), 0).size());
  }
}
