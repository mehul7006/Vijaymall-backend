package com.vijay.mall.wallet.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoinRedemptionTest {
  /**
   * 1 coin = 100 paise, coins may pay at most 50% of the eligible amount
   */
  private static final CoinSettings S = new CoinSettings(100, 50, 12);

  @Test
  void noneUsesNothing() {
    assertEquals(0, CoinRedemption.coinsToUse(100000, CoinRedemption.Mode.NONE, 100, 500, S));
  }

  @Test
  void allUsesTheMaximumAllowed() {
    // 50% of 1000.00 = 500.00 = 500 coins
    assertEquals(500, CoinRedemption.coinsToUse(100000, CoinRedemption.Mode.ALL, 100, 900, S));
  }

  @Test
  void balanceLimitsTheCoins() {
    assertEquals(120, CoinRedemption.coinsToUse(100000, CoinRedemption.Mode.ALL, 100, 120, S));
  }

  @Test
  void percentTakesPartOfTheMaximum() {
    assertEquals(250, CoinRedemption.coinsToUse(100000, CoinRedemption.Mode.PERCENT, 50, 900, S));
  }

  @Test
  void nothingEligibleMeansNoCoins() {
    assertEquals(0, CoinRedemption.coinsToUse(0, CoinRedemption.Mode.ALL, 100, 900, S));
  }

  @Test
  void percentIsClampedTo100() {
    assertEquals(500, CoinRedemption.coinsToUse(100000, CoinRedemption.Mode.PERCENT, 400, 900, S));
  }
}
