package com.vijay.mall.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Boundaries of the earn formulas: one paisa below / at / above each step, minimum order, overflow. */
class CoinMathBoundaryTest {

  @ParameterizedTest(name = "PRO_RATA base={0} spend={1} coins={2} min={3} -> {4}")
  @CsvSource({
      "0,100000,10,0,0",
      "9999,100000,10,0,0",        // 0.99 coin rounds down
      "10000,100000,10,0,1",       // exactly one coin
      "99999,100000,10,0,9",       // one paisa short of 10
      "100000,100000,10,0,10",
      "100001,100000,10,0,10",
      "235000,100000,10,0,23",
      "49999,100000,10,50000,0",   // one paisa below the minimum order
      "50000,100000,10,50000,5",   // exactly the minimum order
      "50001,100000,10,50000,5",
      "1,1,1,0,1",
      "7,3,2,0,4"                  // 7*2/3 = 4.67 -> 4
  })
  void proRataRoundsDownAndHonoursMinimum(long base, long spend, long coins, long min, long expected) {
    assertThat(CoinMath.earn(base, new CoinRule("r", spend, coins, CoinMode.PRO_RATA, min))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "SLAB base={0} spend={1} coins={2} min={3} -> {4}")
  @CsvSource({
      "0,200000,30,0,0",
      "199999,200000,30,0,0",      // one paisa below the first step
      "200000,200000,30,0,30",
      "399999,200000,30,0,30",
      "400000,200000,30,0,60",
      "650000,200000,30,0,90",
      "299999,200000,30,300000,0", // below minimum order although above a step
      "300000,200000,30,300000,30"
  })
  void slabPaysOnlyForFullSteps(long base, long spend, long coins, long min, long expected) {
    assertThat(CoinMath.earn(base, new CoinRule("s", spend, coins, CoinMode.SLAB, min))).isEqualTo(expected);
  }

  @Test
  void proRataOverflowIsReportedNotWrapped() {
    var rule = new CoinRule("big", 100, 10, CoinMode.PRO_RATA, 0);
    assertThatThrownBy(() -> CoinMath.earn(Long.MAX_VALUE, rule)).isInstanceOf(ArithmeticException.class);
  }

  @Test
  void bestPicksTheLargestAndTiesDoNotAdd() {
    var a = new CoinRule("a", 100000, 10, CoinMode.PRO_RATA, 0); // 10 at 100000
    var b = new CoinRule("b", 100000, 10, CoinMode.SLAB, 0);     // also 10
    assertThat(CoinMath.best(100000, List.of(a, b))).isEqualTo(10);
  }

  @Test
  void ruleBelowItsMinimumDoesNotBlockAnotherRule() {
    var strict = new CoinRule("strict", 100, 100, CoinMode.PRO_RATA, 1_000_000);
    var plain = new CoinRule("plain", 100000, 10, CoinMode.PRO_RATA, 0);
    assertThat(CoinMath.best(100000, List.of(strict, plain))).isEqualTo(10);
  }
}
