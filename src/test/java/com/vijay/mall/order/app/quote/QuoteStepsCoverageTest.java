package com.vijay.mall.order.app.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinRedemption;
import com.vijay.mall.wallet.domain.CoinSettings;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteStepsCoverageTest {
  private final PromotionService promotions = mock(PromotionService.class);
  private final OrderRepository orders = mock(OrderRepository.class);
  private final WalletService wallet = mock(WalletService.class);

  private static PricedLine line(long total, boolean redeemable) {
    return new PricedLine(UUID.randomUUID(), UUID.randomUUID(), "x", 1, total, total, redeemable, true);
  }

  private static QuoteContext ctx(UUID user, String coupon, CoinRedemption.Mode mode) {
    var c = new QuoteContext(user, new QuoteRequest(List.of(), coupon, mode, null, Fulfilment.PICKUP));
    c.lines = List.of(line(10000, true));
    c.subtotal = 10000;
    return c;
  }

  @Test
  void couponStepTreatsAMissingSplitAsEmptyAndCapsAtSubtotal() {
    var c = ctx(UUID.randomUUID(), " save ", CoinRedemption.Mode.NONE);
    when(promotions.apply(eq(" save "), any(), any(), anyLong(), any()))
        .thenReturn(new PromotionService.Applied(99999, true, null));
    new CouponStep(promotions, orders).apply(c);
    assertThat(c.couponCode).isEqualTo("SAVE");
    assertThat(c.couponDiscount).isEqualTo(10000);
    assertThat(c.couponByLine).isEmpty();
    assertThat(c.couponBlocksCoins).isFalse();
  }

  @Test
  void priorOrdersAreZeroForAnAnonymousQuoteAndCountedForACustomer() {
    var step = new CouponStep(promotions, orders);
    when(promotions.apply(any(), any(), any(), anyLong(), any())).thenReturn(new PromotionService.Applied(100, false));

    step.apply(ctx(null, "A", CoinRedemption.Mode.NONE));
    var anon = ArgumentCaptor.forClass(IntSupplier.class);
    verify(promotions).apply(eq("A"), eq(null), any(), anyLong(), anon.capture());
    assertThat(anon.getValue().getAsInt()).isZero();

    var user = UUID.randomUUID();
    when(orders.activeOrderCount(user)).thenReturn(3L);
    var c = ctx(user, "B", CoinRedemption.Mode.NONE);
    step.apply(c);
    var known = ArgumentCaptor.forClass(IntSupplier.class);
    verify(promotions).apply(eq("B"), eq(user), any(), anyLong(), known.capture());
    assertThat(known.getValue().getAsInt()).isEqualTo(3);
    assertThat(c.couponBlocksCoins).isTrue();
  }

  private void coins(UUID user, int balance) {
    when(wallet.settings()).thenReturn(new CoinSettings(100, 50, 12));
    when(wallet.balance(user)).thenReturn(balance);
  }

  @Test
  void coinStepWarnsOnlyWhenCoinsAreUsedAndSomeItemsCannotRedeem() {
    var step = new CoinRedemptionStep(wallet);

    // part of the cart can redeem and coins are spent: warning
    var user = UUID.randomUUID();
    coins(user, 1000);
    var mixed = ctx(user, null, CoinRedemption.Mode.ALL);
    mixed.lines = List.of(line(6000, true), line(4000, false));
    step.apply(mixed);
    assertThat(mixed.coinsUsed).isEqualTo(30); // 50% of 6000 paise at 100 paise a coin
    assertThat(mixed.coinDiscount).isEqualTo(3000);
    assertThat(mixed.warnings).containsExactly("Some items cannot be paid with coins");

    // the whole cart can redeem: no warning
    var user2 = UUID.randomUUID();
    coins(user2, 1000);
    var all = ctx(user2, null, CoinRedemption.Mode.ALL);
    step.apply(all);
    assertThat(all.coinsUsed).isEqualTo(50);
    assertThat(all.warnings).isEmpty();

    // some items cannot redeem but the customer has no coins: no warning
    var user3 = UUID.randomUUID();
    coins(user3, 0);
    var broke = ctx(user3, null, CoinRedemption.Mode.ALL);
    broke.lines = List.of(line(6000, true), line(4000, false));
    step.apply(broke);
    assertThat(broke.coinsUsed).isZero();
    assertThat(broke.warnings).isEmpty();
  }

  @Test
  void coinStepSkipsWhenModeIsNoneOrCouponBlocksCoins() {
    var step = new CoinRedemptionStep(wallet);
    var none = ctx(UUID.randomUUID(), null, CoinRedemption.Mode.NONE);
    step.apply(none);
    assertThat(none.warnings).isEmpty();

    var blocked = ctx(UUID.randomUUID(), "X", CoinRedemption.Mode.ALL);
    blocked.couponCode = "X";
    blocked.couponBlocksCoins = true;
    step.apply(blocked);
    assertThat(blocked.warnings).containsExactly("Coins cannot be used together with coupon X");
    assertThat(blocked.coinsUsed).isZero();
  }
}
