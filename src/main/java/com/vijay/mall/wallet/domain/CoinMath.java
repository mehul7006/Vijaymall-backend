package com.vijay.mall.wallet.domain;

import java.util.Collection;

/** Pure coin arithmetic. No Spring, no database. */
public final class CoinMath {
  private CoinMath() {}

  /** Whole coins earned on basePaise under one rule. */
  public static long earn(long basePaise, CoinRule rule) {
    if (basePaise < rule.minOrderPaise()) return 0;
    return switch (rule.mode()) {
      case PRO_RATA -> Math.multiplyExact(basePaise, rule.coins()) / rule.spendPaise();
      case SLAB -> (basePaise / rule.spendPaise()) * rule.coins();
    };
  }

  /** The best single rule wins; rules never add up. */
  public static long best(long basePaise, Collection<CoinRule> activeRules) {
    return activeRules.stream().mapToLong(r -> earn(basePaise, r)).max().orElse(0);
  }
}
