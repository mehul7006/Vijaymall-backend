package com.vijay.mall.common.persistence;

import com.vijay.mall.common.Page;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Totals for paged lists that are always correct and always cheap. Counting every row of a big table on every request is the
 * expensive part of a list (hundreds of thousands of rows, paid again for each caller), and a cached total would be out of date.
 * Instead:
 * <ul>
 *   <li>the page is read with one extra row, which tells exactly whether another page follows;</li>
 *   <li>when no page follows, the total is {@code offset + rows shown}: exact, with no count at all (except a page past the end, which
 *       shows nothing, so the real bounded count is asked for);</li>
 *   <li>otherwise rows are counted but never more than {@link #CAP} + 1, so the cost has a ceiling however big the table grows.
 *       Up to {@link #CAP} rows the total is exact; beyond it the page says so ({@code totalExact=false}) and the app shows
 *       "10,000+" while paging still works through {@code hasMore}.</li>
 * </ul>
 */
public final class BoundedCount {
  /** The most rows a total counts to; a list longer than this reports this number with {@code totalExact=false}. */
  public static final int CAP = 10_000;

  private BoundedCount() { }

  /**
   * Builds the page from one extra-row read.
   *
   * @param fetched the rows read: up to {@code size + 1} (see {@link Window})
   * @param page zero-based page number
   * @param size rows shown per page
   * @param boundedCount counts the whole list up to {@code CAP + 1} rows; only called when another page follows
   * @param <T> the row type
   * @return the page with its total, {@code totalExact} and {@code hasMore}
   */
  public static <T> Page<T> assemble(List<T> fetched, int page, int size, LongSupplier boundedCount) {
    boolean hasMore = fetched.size() > size;
    List<T> items = hasMore ? List.copyOf(fetched.subList(0, size)) : List.copyOf(fetched);
    long shown = (long) page * size + items.size();
    if (!hasMore && (page == 0 || !items.isEmpty())) return new Page<>(items, page, size, shown, true, false);
    if (!hasMore) { // a page past the end: nothing to show, so the offset says nothing about the total; ask for the real bounded count
      long counted = boundedCount.getAsLong();
      return new Page<>(items, page, size, Math.min(counted, CAP), counted <= CAP, false);
    }
    long counted = boundedCount.getAsLong();
    boolean exact = counted <= CAP;
    long total = Math.max(Math.min(counted, CAP), shown + 1); // rows may have been added or removed between the two reads
    return new Page<>(items, page, size, total, exact, true);
  }

  /**
   * Counts the rows of a native {@code FROM ... WHERE ...} clause, never more than {@code CAP + 1}.
   *
   * @param em the entity manager
   * @param fromWhere the table and filter, for example {@code "audit_log a where a.action = :action"}
   * @param params named parameters of the filter
   * @return the number of rows, at most {@code CAP + 1}
   */
  public static long count(EntityManager em, String fromWhere, Map<String, Object> params) {
    Query q = em.createNativeQuery("select count(*) from (select 1 from " + fromWhere + " limit " + (CAP + 1) + ") bounded");
    params.forEach(q::setParameter);
    return ((Number) q.getSingleResult()).longValue();
  }
}
