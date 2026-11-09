package com.vijay.mall.order.domain;

import java.util.Arrays;
import java.util.stream.IntStream;

/** Money maths for returns on online orders, in paise, exact: many small returns add up to exactly the whole line. */
public final class OrderReturnMath {
  private OrderReturnMath() {}

  /**
   * Spreads {@code amount} over {@code weights} in proportion, to the paisa (the biggest fractions get the leftover paise,
   * earlier lines first on a tie). The result always sums to {@code amount}.
   */
  public static long[] allocate(long amount, long[] weights) {
    long total = Arrays.stream(weights).sum();
    if (amount <= 0 || total <= 0) return new long[weights.length];
    long[] base = Arrays.stream(weights).map(w -> Math.multiplyExact(amount, w) / total).toArray();
    long[] rem = Arrays.stream(weights).map(w -> Math.multiplyExact(amount, w) % total).toArray();
    long left = amount - Arrays.stream(base).sum();
    Integer[] order = IntStream.range(0, weights.length).boxed().toArray(Integer[]::new);
    Arrays.sort(order, (a, b) -> rem[b] != rem[a] ? Long.compare(rem[b], rem[a]) : Integer.compare(a, b));
    for (int k = 0; k < left; k++) base[order[k]]++;
    return base;
  }

  /** What returning {@code returning} more of {@code sold} pieces is worth, given {@code already} were returned before. */
  public static long share(long amount, int sold, int already, int returning) {
    return roundShare(amount, already + returning, sold) - roundShare(amount, already, sold);
  }

  private static long roundShare(long amount, int pieces, int sold) {
    return Math.floorDiv(Math.multiplyExact(amount, (long) pieces) * 2 + sold, 2L * sold);
  }

  /** Coins (rounded down) for a cumulative share of the order, minus what earlier returns already moved. */
  public static int coinsDelta(int coins, long returnedGross, long subtotal, int alreadyMoved) {
    if (coins <= 0 || subtotal <= 0) return 0;
    long cumulative = Math.floorDiv(Math.multiplyExact((long) coins, returnedGross), subtotal);
    return (int) Math.max(0, cumulative - alreadyMoved);
  }
}
