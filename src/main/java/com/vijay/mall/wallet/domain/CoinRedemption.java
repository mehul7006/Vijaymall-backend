package com.vijay.mall.wallet.domain;

/** Pure maths for paying part of an order with coins. */
public final class CoinRedemption {
  private CoinRedemption() {}

  public enum Mode { NONE, ALL, PERCENT }

  /**
   * @param redeemableBasePaise value of items that may be paid with coins, after coupon
   * @param mode                NONE, ALL or PERCENT (percent of the allowed maximum)
   * @param percent             used only for PERCENT, 0..100
   * @return coins to spend, never more than the balance
   */
  public static int coinsToUse(long redeemableBasePaise, Mode mode, int percent, int balance, CoinSettings s) {
    if (mode == Mode.NONE || balance <= 0 || redeemableBasePaise <= 0) return 0;
    long cap = redeemableBasePaise * s.maxRedeemPercent() / 100;
    long want = mode == Mode.ALL ? cap : cap * Math.max(0, Math.min(percent, 100)) / 100;
    long coins = want / s.paisePerCoin();
    return (int) Math.min(coins, balance);
  }
}
