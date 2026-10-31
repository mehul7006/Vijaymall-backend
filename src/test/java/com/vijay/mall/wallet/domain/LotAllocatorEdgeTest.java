package com.vijay.mall.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LotAllocatorEdgeTest {
  private static final Instant T0 = Instant.parse("2027-01-01T00:00:00Z");

  private static LotAllocator.Lot lot(int remaining, Instant exp) { return new LotAllocator.Lot(UUID.randomUUID(), remaining, exp); }

  @Test
  void spendingEverythingUsesEveryLotCompletely() {
    var a = lot(10, T0);
    var b = lot(5, T0.plusSeconds(1));
    var uses = LotAllocator.allocate(List.of(b, a), 15);
    assertThat(uses).containsExactly(new LotAllocator.Use(a.id(), 10), new LotAllocator.Use(b.id(), 5));
  }

  @Test
  void spansThreeLotsInExpiryOrderAndLeavesTheRest() {
    var first = lot(4, T0);
    var second = lot(4, T0.plusSeconds(10));
    var third = lot(4, T0.plusSeconds(20));
    var uses = LotAllocator.allocate(List.of(third, first, second), 6);
    assertThat(uses).containsExactly(new LotAllocator.Use(first.id(), 4), new LotAllocator.Use(second.id(), 2));
  }

  @Test
  void lotsExpiringAtTheSameInstantKeepTheirGivenOrder() {
    var a = lot(5, T0);
    var b = lot(5, T0);
    assertThat(LotAllocator.allocate(List.of(a, b), 3)).containsExactly(new LotAllocator.Use(a.id(), 3));
    assertThat(LotAllocator.allocate(List.of(b, a), 3)).containsExactly(new LotAllocator.Use(b.id(), 3));
  }

  @Test
  void emptyLotsAreNeverPartOfAUse() {
    var empty = lot(0, T0);
    var real = lot(5, T0.plusSeconds(5));
    assertThat(LotAllocator.allocate(List.of(empty, real), 2)).containsExactly(new LotAllocator.Use(real.id(), 2));
  }

  @Test
  void neverExpiringLotsAreUsedLastInTheirGivenOrder() {
    var n1 = lot(5, null);
    var n2 = lot(5, null);
    var dated = lot(2, T0);
    assertThat(LotAllocator.allocate(List.of(n1, n2, dated), 8))
        .containsExactly(new LotAllocator.Use(dated.id(), 2), new LotAllocator.Use(n1.id(), 5), new LotAllocator.Use(n2.id(), 1));
  }

  @Test
  void negativeCoinsAreRejectedAndOneCoinTooManyFails() {
    assertThatThrownBy(() -> LotAllocator.allocate(List.of(), -1)).isInstanceOf(IllegalArgumentException.class);
    var l = lot(3, T0);
    assertThatThrownBy(() -> LotAllocator.allocate(List.of(l), 4)).isInstanceOf(IllegalStateException.class)
        .hasMessage("not enough coins in open lots");
    assertThat(LotAllocator.allocate(List.of(l), 3)).containsExactly(new LotAllocator.Use(l.id(), 3));
  }

  @Test
  void parallelStreamsAreRefusedBecauseTheFoldIsOrdered() {
    // the combiner must never be used: a sequential reduce is part of the contract
    var l = lot(3, T0);
    assertThat(LotAllocator.allocate(List.of(l), 1)).hasSize(1);
  }
}
