package com.vijay.mall.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OrderReturnMathTest {
  @Test
  void allocateAlwaysSumsToTheAmountAndFavoursTheBiggestFractions() {
    long[] parts = OrderReturnMath.allocate(100, new long[] {1, 1, 1});
    assertThat(Arrays.stream(parts).sum()).isEqualTo(100);
    assertThat(parts).containsExactly(34, 33, 33);
    assertThat(OrderReturnMath.allocate(10, new long[] {5000, 5000})).containsExactly(5, 5);
    assertThat(Arrays.stream(OrderReturnMath.allocate(7, new long[] {3, 9, 1})).sum()).isEqualTo(7);
  }

  @Test
  void nothingToSpreadOrNoWeightGivesZeros() {
    assertThat(OrderReturnMath.allocate(0, new long[] {1, 2})).containsExactly(0, 0);
    assertThat(OrderReturnMath.allocate(-5, new long[] {1, 2})).containsExactly(0, 0);
    assertThat(OrderReturnMath.allocate(50, new long[] {0, 0})).containsExactly(0, 0);
  }

  @Test
  void manySmallReturnsAddUpToExactlyTheWholeLine() {
    long amount = 1001; // 3 pieces
    long total = OrderReturnMath.share(amount, 3, 0, 1) + OrderReturnMath.share(amount, 3, 1, 1) + OrderReturnMath.share(amount, 3, 2, 1);
    assertThat(total).isEqualTo(amount);
    assertThat(OrderReturnMath.share(amount, 3, 0, 3)).isEqualTo(amount);
    assertThat(OrderReturnMath.share(amount, 3, 1, 0)).isZero();
  }

  @Test
  void coinsFollowTheReturnedShareAndNeverGoBelowWhatMovedAlready() {
    assertThat(OrderReturnMath.coinsDelta(20, 2500, 10000, 0)).isEqualTo(5);
    assertThat(OrderReturnMath.coinsDelta(20, 5000, 10000, 5)).isEqualTo(5);
    assertThat(OrderReturnMath.coinsDelta(20, 10000, 10000, 20)).isZero();
    assertThat(OrderReturnMath.coinsDelta(20, 100, 10000, 3)).isZero();
    assertThat(OrderReturnMath.coinsDelta(0, 5000, 10000, 0)).isZero();
    assertThat(OrderReturnMath.coinsDelta(20, 5000, 0, 0)).isZero();
  }
}
