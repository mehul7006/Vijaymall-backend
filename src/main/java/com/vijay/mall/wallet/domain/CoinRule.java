package com.vijay.mall.wallet.domain;

/** "On shopping of spendPaise you get coins coins." All money is in paise. */
public record CoinRule(String name, long spendPaise, long coins, CoinMode mode, long minOrderPaise) {
  /**
   * Validates the rule.
   * @throws IllegalArgumentException when a field is missing or out of range
   */
  public CoinRule {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    if (spendPaise <= 0) throw new IllegalArgumentException("spendPaise must be positive");
    if (coins <= 0) throw new IllegalArgumentException("coins must be positive");
    if (mode == null) throw new IllegalArgumentException("mode is required");
    if (minOrderPaise < 0) throw new IllegalArgumentException("minOrderPaise cannot be negative");
  }
}
