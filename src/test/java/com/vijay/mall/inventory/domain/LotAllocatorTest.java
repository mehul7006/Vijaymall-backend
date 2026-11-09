package com.vijay.mall.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LotAllocatorTest {
  private static final UUID P = UUID.randomUUID();
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static Lot lot(int days, int remaining, String cost, Long price, Long mrp, LocalDate expiry) {
    return new Lot(UUID.randomUUID(), P, T0.plusSeconds(days * 86400L), 100, remaining, new BigDecimal(cost), true, mrp, price, "", expiry, "PURCHASE");
  }

  @Test
  void takesOldestFirstAcrossLotsAndKeepsTheRoundingRemainderOnTheLastLot() {
    Lot a = lot(0, 2, "10.3333", null, null, null);
    Lot b = lot(1, 5, "10.3333", null, null, null);
    var r = LotAllocator.allocate(List.of(a, b), 4);
    assertThat(r.missing()).isZero();
    assertThat(r.takes()).extracting(LotAllocator.Take::qty).containsExactly(2, 2);
    // exact cost 4 x 10.3333 = 41.3332 -> 41; first part rounds to 21 (20.6666), the last lot holds the remainder
    assertThat(r.costPaise()).isEqualTo(41);
    assertThat(r.takes().get(0).costPaise()).isEqualTo(21);
    assertThat(r.takes().get(1).costPaise()).isEqualTo(20);
  }

  @Test
  void reportsMissingUnitsAndSkipsEmptyLotsAndZeroQty() {
    Lot a = lot(0, 0, "5", null, null, null);
    Lot b = lot(1, 3, "5", null, null, null);
    var r = LotAllocator.allocate(List.of(a, b), 5);
    assertThat(r.missing()).isEqualTo(2);
    assertThat(r.takes()).hasSize(1);
    assertThat(LotAllocator.allocate(List.of(b), 0).takes()).isEmpty();
  }

  @Test
  void fefoPutsTheEarliestExpiryFirstAndUndatedLast() {
    Lot late = lot(0, 5, "1", null, null, LocalDate.of(2027, 1, 1));
    Lot soon = lot(1, 5, "1", null, null, LocalDate.of(2026, 6, 1));
    Lot none = lot(2, 5, "1", null, null, null);
    assertThat(List.of(none, late, soon).stream().sorted(Lot.FEFO).toList()).containsExactly(soon, late, none);
    assertThat(List.of(none, late, soon).stream().sorted(Lot.FIFO).toList()).containsExactly(late, soon, none);
  }

  @Test
  void aReturnedCostAddsUpToTheAllocationCost() {
    long first = LotAllocator.returnedCost(100, 3, 0, 1);
    long second = LotAllocator.returnedCost(100, 3, 1, 1);
    long third = LotAllocator.returnedCost(100, 3, 2, 1);
    assertThat(first + second + third).isEqualTo(100);
  }

  @Test
  void priceRunsSplitUnitsByLotPriceAndMergeEqualPrices() {
    Lot a = lot(0, 3, "1", 100L, 120L, null);
    Lot same = lot(1, 2, "1", 100L, 120L, null);
    Lot dear = lot(2, 10, "1", 120L, 130L, null);
    var runs = LotAllocator.priceRuns(List.of(a, same, dear), 7, 0);
    assertThat(runs).extracting(LotAllocator.Run::qty).containsExactly(5, 2);
    assertThat(runs.get(1).lot()).isEqualTo(dear);
    // skipping 4 units (already priced as a flash sale) starts inside the second lot
    var skipped = LotAllocator.priceRuns(List.of(a, same, dear), 3, 4);
    assertThat(skipped).extracting(LotAllocator.Run::qty).containsExactly(1, 2);
    // more than the stock: the extra is priced like the last run
    assertThat(LotAllocator.priceRuns(List.of(a), 5, 0)).singleElement().satisfies(x -> assertThat(x.qty()).isEqualTo(5));
    // everything skipped, or no lots: nothing to price
    assertThat(LotAllocator.priceRuns(List.of(a), 2, 3)).isEmpty();
    assertThat(LotAllocator.priceRuns(List.of(), 2, 0)).isEmpty();
  }

  @Test
  void lotInfoAnnouncesTheNextPriceChange() {
    Lot a = lot(0, 3, "1", 100L, 120L, null);
    Lot same = lot(1, 2, "1", 100L, 120L, null);
    Lot dear = lot(2, 10, "1", 120L, null, null);
    var info = LotInfo.of(List.of(a, same, dear), true, 100, 125).orElseThrow();
    assertThat(info.lotId()).isEqualTo(a.id());
    assertThat(info.qtyLeftInLot()).isEqualTo(3);
    assertThat(info.nextPriceChange()).isEqualTo(new LotInfo.NextPriceChange(5, 120, 125));
    assertThat(LotInfo.of(List.of(a, same), true, 100, 120).orElseThrow().nextPriceChange()).isNull();
    assertThat(LotInfo.of(List.of(a, dear), false, 100, 120).orElseThrow().nextPriceChange()).isNull();
    assertThat(LotInfo.of(List.of(), true, 1, 1)).isEmpty();
    assertThat(a.withRemaining(1).qtyRemaining()).isEqualTo(1);
  }
}
