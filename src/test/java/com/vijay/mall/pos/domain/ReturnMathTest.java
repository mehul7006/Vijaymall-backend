package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReturnMathTest {
  @Test void roundShare_halfUp() {
    assertThat(ReturnMath.roundShare(100, 1, 3)).isEqualTo(33);
    assertThat(ReturnMath.roundShare(100, 2, 3)).isEqualTo(67);
    assertThat(ReturnMath.roundShare(5, 1, 2)).isEqualTo(3);
    assertThat(ReturnMath.roundShare(100, 3, 3)).isEqualTo(100);
    assertThat(ReturnMath.roundShare(100, 0, 3)).isZero();
  }
  @Test void roundShare_badInput_throws() {
    assertThatThrownBy(() -> ReturnMath.roundShare(1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReturnMath.roundShare(1, 4, 3)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReturnMath.roundShare(1, -1, 3)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void shareOfReturn_manySmallReturns_sumToWhole() {
    long net = 1001;
    long total = 0;
    for (int i = 0; i < 7; i++) total += ReturnMath.shareOfReturn(net, 7, i, 1);
    assertThat(total).isEqualTo(net);
  }
  @Test void shareOfReturn_splitIntoTwoReturns_sumsToWhole() {
    long a = ReturnMath.shareOfReturn(1000, 3, 0, 2);
    long b = ReturnMath.shareOfReturn(1000, 3, 2, 1);
    assertThat(a + b).isEqualTo(1000);
  }
  @Test void coinsDelta_proportionalAndCumulative() {
    assertThat(ReturnMath.coinsDelta(10, 5000, 10000, 0)).isEqualTo(5);
    assertThat(ReturnMath.coinsDelta(10, 10000, 10000, 5)).isEqualTo(5);
    assertThat(ReturnMath.coinsDelta(10, 3333, 10000, 0)).isEqualTo(3);
  }
  @Test void coinsDelta_neverNegative_andZeroCases() {
    assertThat(ReturnMath.coinsDelta(10, 1000, 10000, 5)).isZero();
    assertThat(ReturnMath.coinsDelta(0, 1000, 10000, 0)).isZero();
    assertThat(ReturnMath.coinsDelta(10, 1000, 0, 0)).isZero();
  }
}
