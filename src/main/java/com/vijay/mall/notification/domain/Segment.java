package com.vijay.mall.notification.domain;

import java.util.List;

/**
 * A set of conditions that picks customers. {@code match} {@code ALL} means a customer must meet every condition, {@code ANY} at
 * least one.
 *
 * @param match {@code ALL} (and) or {@code ANY} (or)
 * @param rules the conditions, 1 to {@value #MAX_RULES}
 */
public record Segment(String match, List<Rule> rules) {
  /** Most conditions one segment may hold. */
  public static final int MAX_RULES = 10;

  /** True when a customer must meet every condition. */
  public boolean matchAll() { return "ALL".equals(match); }
}
