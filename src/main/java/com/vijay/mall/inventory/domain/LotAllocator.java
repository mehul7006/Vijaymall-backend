package com.vijay.mall.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure rules for taking units out of lots. The lots are given already in the order they must be used (oldest first, or earliest
 * expiry first); nothing here touches a database.
 */
public final class LotAllocator {
  private LotAllocator() {}

  /** Units taken from one lot, at that lot's unit cost; {@code costPaise} is the whole-paise cost of these units. */
  public record Take(UUID lotId, int qty, BigDecimal unitCost, boolean costKnown, long costPaise) {}

  /** What was taken and how many units no lot could give. */
  public record Result(List<Take> takes, int missing) {
    /** The whole-paise cost of everything taken. */
    public long costPaise() { return takes.stream().mapToLong(Take::costPaise).sum(); }
  }

  /**
   * Takes {@code qty} units from the lots in the order given. The lots' remaining quantities are NOT changed here. The cost of the
   * line is the exact sum of units times unit cost rounded half up to a whole paisa once; the rounding remainder is put on the last
   * lot used, so the parts always add up to the line cost.
   *
   * @param lots the lots with stock, in the order to use them
   * @param qty the units wanted (zero or more)
   * @return the takes (one per lot used) and the units still missing
   */
  public static Result allocate(List<Lot> lots, int qty) {
    List<Take> parts = new ArrayList<>();
    int left = qty;
    BigDecimal exact = BigDecimal.ZERO;
    for (Lot l : lots) {
      if (left <= 0) break;
      int n = Math.min(left, l.qtyRemaining());
      if (n <= 0) continue;
      BigDecimal cost = l.unitCost().multiply(BigDecimal.valueOf(n));
      exact = exact.add(cost);
      parts.add(new Take(l.id(), n, l.unitCost(), l.costKnown(), cost.setScale(0, RoundingMode.HALF_UP).longValueExact()));
      left -= n;
    }
    if (parts.size() > 1) {
      long total = exact.setScale(0, RoundingMode.HALF_UP).longValueExact();
      long others = 0;
      for (int i = 0; i < parts.size() - 1; i++) others += parts.get(i).costPaise();
      Take last = parts.getLast();
      parts.set(parts.size() - 1, new Take(last.lotId(), last.qty(), last.unitCost(), last.costKnown(), total - others));
    }
    return new Result(parts, left);
  }

  /**
   * The cost to take back when {@code returning} more units of an allocation come back: the share of the allocation's cost up to
   * the new returned count, less what was already taken back, so the pieces of a fully returned allocation add up to its cost.
   *
   * @param costPaise the cost of the whole allocation
   * @param qty the units in the allocation
   * @param alreadyReturned units returned before
   * @param returning units returned now
   */
  public static long returnedCost(long costPaise, int qty, int alreadyReturned, int returning) {
    return share(costPaise, qty, alreadyReturned + returning) - share(costPaise, qty, alreadyReturned);
  }

  private static long share(long costPaise, int qty, int units) {
    return BigDecimal.valueOf(costPaise).multiply(BigDecimal.valueOf(units)).divide(BigDecimal.valueOf(qty), 0, RoundingMode.HALF_UP).longValueExact();
  }

  /**
   * Splits {@code qty} units over the lots in the order given into runs of equal price: each run is how many units go at one
   * price and MRP. {@code skip} units are passed over first (units already priced another way, such as flash-sale units).
   * Units beyond the stock are priced like the last run.
   *
   * @param lots the lots with stock, in the order to use them
   * @param qty the units to price
   * @param skip units to pass over before pricing starts
   * @return the runs in order; empty when there are no lots
   */
  public static List<Run> priceRuns(List<Lot> lots, int qty, int skip) {
    List<Run> runs = new ArrayList<>();
    int toSkip = skip;
    int left = qty;
    Lot lastUsed = null;
    for (Lot l : lots) {
      if (left <= 0) break;
      int available = l.qtyRemaining();
      if (toSkip >= available) {
        toSkip -= available;
        continue;
      }
      available -= toSkip;
      toSkip = 0;
      int n = Math.min(left, available);
      lastUsed = l;
      if (!runs.isEmpty() && !runs.getLast().lot().priceDiffersFrom(l)) {
        Run prev = runs.removeLast();
        runs.add(new Run(prev.lot(), prev.qty() + n));
      } else {
        runs.add(new Run(l, n));
      }
      left -= n;
    }
    if (left > 0 && lastUsed != null) {
      Run prev = runs.removeLast();
      runs.add(new Run(prev.lot(), prev.qty() + left));
    }
    return runs;
  }

  /** A number of units sold at the price and MRP of {@code lot}. */
  public record Run(Lot lot, int qty) {}
}
