package com.vijay.mall.common;

import java.util.List;

/**
 * Paged list in the shape of the API contract.
 *
 * <p>{@code total} is always correct and never stale: it is exact up to {@link com.vijay.mall.common.persistence.BoundedCount#CAP}
 * rows. Beyond that the list is too long to count cheaply on every request, so {@code total} is that cap, {@code totalExact} is
 * {@code false} (show "10,000+") and {@code hasMore} still says exactly whether another page follows.
 *
 * @param items the rows of this page
 * @param page zero-based page number
 * @param size page size
 * @param total how many rows the list has (see above)
 * @param totalExact false when {@code total} is only the cap and the list has more
 * @param hasMore whether another page follows this one
 */
public record Page<T>(List<T> items, int page, int size, long total, boolean totalExact, boolean hasMore) {
  /** A page whose total is exact; {@code hasMore} is worked out from the total. */
  public Page(List<T> items, int page, int size, long total) {
    this(items, page, size, total, true, (long) page * size + items.size() < total);
  }

  /**
   * The same page with other rows (for example list rows built from entities), keeping the total, {@code totalExact} and
   * {@code hasMore}: use it instead of building a new page from {@code total()} alone, which would lose them.
   *
   * @param newItems the rows to show instead
   * @param <R> their type
   * @return the page with the same position and totals
   */
  public <R> Page<R> withItems(List<R> newItems) { return new Page<>(newItems, page, size, total, totalExact, hasMore); }

  /** Limits a requested page size to 1 to 50. */
  public static int clampSize(int size) { return Math.max(1, Math.min(size, 50)); }
}
