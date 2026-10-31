package com.vijay.mall.wallet.domain;

/** How a coin rule turns spend into coins. */
public enum CoinMode {
  /** Coins in proportion to spend: floor(base * coins / spend). */
  PRO_RATA,
  /** Coins only for each full step of spend: floor(base / spend) * coins. */
  SLAB
}
