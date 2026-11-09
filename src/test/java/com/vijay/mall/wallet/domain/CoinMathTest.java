package com.vijay.mall.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class CoinMathTest {
  /** paise */
  private static final long RUPEE = 100;

  private static CoinRule proRata(long spendRupees, long coins) {
    return new CoinRule("standard", spendRupees * RUPEE, coins, CoinMode.PRO_RATA, 0);
  }
  private static CoinRule slab(long spendRupees, long coins) {
    return new CoinRule("diwali", spendRupees * RUPEE, coins, CoinMode.SLAB, 0);
  }

  @Test void proRata_spend1000_gets10() { assertThat(CoinMath.earn(1000 * RUPEE, proRata(1000, 10))).isEqualTo(10); }
  @Test void proRata_2350_gets23() { assertThat(CoinMath.earn(2350 * RUPEE, proRata(1000, 10))).isEqualTo(23); }
  @Test void proRata_rounds_down() { assertThat(CoinMath.earn(99 * RUPEE, proRata(1000, 10))).isZero(); }
  @Test void slab_2350_with_2000_for_30_gets30() { assertThat(CoinMath.earn(2350 * RUPEE, slab(2000, 30))).isEqualTo(30); }
  @Test void slab_below_first_step_gets_zero() { assertThat(CoinMath.earn(1999 * RUPEE, slab(2000, 30))).isZero(); }
  @Test void slab_4000_gets_60() { assertThat(CoinMath.earn(4000 * RUPEE, slab(2000, 30))).isEqualTo(60); }
  @Test void below_min_order_gets_zero() {
    var r = new CoinRule("min", 1000 * RUPEE, 10, CoinMode.PRO_RATA, 500 * RUPEE);
    assertThat(CoinMath.earn(400 * RUPEE, r)).isZero();
  }
  @Test void best_rule_wins_and_rules_do_not_add() {
    var rules = List.of(proRata(1000, 10), slab(2000, 30));
    assertThat(CoinMath.best(2350 * RUPEE, rules)).isEqualTo(30);
    assertThat(CoinMath.best(1000 * RUPEE, rules)).isEqualTo(10);
  }
  @Test void no_rules_means_zero() { assertThat(CoinMath.best(5000 * RUPEE, List.of())).isZero(); }
  @Test void rule_rejects_nonsense() {
    Assertions.assertThatThrownBy(() -> new CoinRule("bad", 0, 10, CoinMode.PRO_RATA, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
