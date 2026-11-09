package com.vijay.mall.wallet.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Pure FIFO rule: spend coins from the lots that expire first. Lots that never expire are used last. */
public final class LotAllocator {
  private LotAllocator() {}

  /**
   * An earning lot that may still hold coins.
   * @param id the earning ledger row
   * @param remaining coins left in it
   * @param expiresAt when it expires, null if never
   */
  public record Lot(UUID id, int remaining, Instant expiresAt) {}

  /**
   * Coins to take from one lot.
   * @param lotId the lot
   * @param coins how many coins
   */
  public record Use(UUID lotId, int coins) {}

  /** Immutable fold state: coins still to find, and the uses chosen so far. */
  private record Pick(int need, List<Use> uses) {
    Pick from(Lot lot) {
      int take = Math.min(lot.remaining(), need);
      return take <= 0 ? this : new Pick(need - take, Stream.concat(uses.stream(), Stream.of(new Use(lot.id(), take))).toList());
    }
  }

  private static final Comparator<Lot> SOONEST_FIRST =
      Comparator.comparing(Lot::expiresAt, Comparator.nullsLast(Comparator.naturalOrder()));

  /**
   * Chooses which lots to take {@code coins} from, soonest expiry first.
   * @throws IllegalArgumentException if coins is negative
   * @throws IllegalStateException if the lots do not hold enough coins
   */
  public static List<Use> allocate(List<Lot> lots, int coins) {
    if (coins < 0) throw new IllegalArgumentException("coins cannot be negative");
    Pick result = lots.stream().sorted(SOONEST_FIRST)
        .reduce(new Pick(coins, List.of()), Pick::from, (_, _) -> { throw new UnsupportedOperationException("sequential only"); });
    if (result.need() > 0) throw new IllegalStateException("not enough coins in open lots");
    return result.uses();
  }
}
