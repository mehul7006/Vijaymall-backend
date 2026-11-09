package com.vijay.mall.common.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A page request for "this page plus one more row": the offset is {@code page * size} but {@code size + 1} rows are read, so the
 * caller can tell exactly whether another page follows without counting anything.
 */
public final class Window implements Pageable {
  private final int page;
  private final int size;
  private final Sort sort;

  private Window(int page, int size, Sort sort) {
    this.page = page;
    this.size = size;
    this.sort = sort;
  }

  /**
   * Creates the request.
   *
   * @param page zero-based page number
   * @param size how many rows the page shows (one more is read)
   * @param sort the order of the rows
   * @return the request
   */
  public static Window of(int page, int size, Sort sort) { return new Window(page, size, sort); }

  @Override public int getPageNumber() { return page; }

  /** One more than the page shows: the extra row only proves that another page follows. */
  @Override public int getPageSize() { return size + 1; }

  @Override public long getOffset() { return (long) page * size; }

  @Override public Sort getSort() { return sort; }

  @Override public Pageable next() { return new Window(page + 1, size, sort); }

  @Override public Pageable previousOrFirst() { return new Window(Math.max(0, page - 1), size, sort); }

  @Override public Pageable first() { return new Window(0, size, sort); }

  @Override public Pageable withPage(int pageNumber) { return new Window(pageNumber, size, sort); }

  @Override public boolean hasPrevious() { return page > 0; }
}
