package com.vijay.mall.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.wallet.domain.CoinRedemption.Mode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CartTest {
  private static final UUID A = UUID.randomUUID();
  private static final UUID B = UUID.randomUUID();

  @Test
  void emptyCartHasNoLinesAndNoCoinsAndNoCoupon() {
    var c = Cart.empty();
    assertThat(c.items()).isEmpty();
    assertThat(c.couponCode()).isNull();
    assertThat(c.coinMode()).isEqualTo(Mode.NONE);
    assertThat(c.percent()).isNull();
    assertThat(c.updatedAt()).isNull();
  }

  @Test
  void repeatedProductsAreMergedAndKeepTheirFirstPosition() {
    var c = Cart.normalized(List.of(new Cart.Line(B, 1), new Cart.Line(A, 2), new Cart.Line(B, 3)), null, null, null);
    assertThat(c.items()).containsExactly(new Cart.Line(B, 4), new Cart.Line(A, 2));
  }

  @Test
  void mergedQuantityIsCapped() {
    var c = Cart.normalized(List.of(new Cart.Line(A, 900), new Cart.Line(A, 900)), null, null, null);
    assertThat(c.items()).containsExactly(new Cart.Line(A, Cart.MAX_QTY));
  }

  @Test
  void couponIsTrimmedUpperCasedAndBlankBecomesNull() {
    assertThat(Cart.normalized(List.of(), "  fest50 ", null, null).couponCode()).isEqualTo("FEST50");
    assertThat(Cart.normalized(List.of(), "   ", null, null).couponCode()).isNull();
    assertThat(Cart.normalized(List.of(), null, null, null).couponCode()).isNull();
  }

  @Test
  void percentIsKeptOnlyForPercentModeAndClamped() {
    assertThat(Cart.normalized(List.of(), null, Mode.PERCENT, 40).percent()).isEqualTo(40);
    assertThat(Cart.normalized(List.of(), null, Mode.PERCENT, 400).percent()).isEqualTo(100);
    assertThat(Cart.normalized(List.of(), null, Mode.PERCENT, -5).percent()).isEqualTo(0);
    assertThat(Cart.normalized(List.of(), null, Mode.PERCENT, null).percent()).isEqualTo(0);
    assertThat(Cart.normalized(List.of(), null, Mode.ALL, 40).percent()).isNull();
    assertThat(Cart.normalized(List.of(), null, null, 40).percent()).isNull();
    assertThat(Cart.normalized(List.of(), null, null, 40).coinMode()).isEqualTo(Mode.NONE);
  }

  @Test
  void nullLinesMeanAnEmptyBasket() {
    assertThat(Cart.normalized(null, null, null, null).items()).isEmpty();
  }
}
