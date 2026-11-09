package com.vijay.mall.common;

import java.util.List;

/** Paged list in the shape of the API contract. */
public record Page<T>(List<T> items, int page, int size, long total) {
  /** Limits a requested page size to 1 to 50. */
  public static int clampSize(int size) { return Math.max(1, Math.min(size, 50)); }
}
