package com.vijay.mall.order.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.order.app.quote.CoinRedemptionStep;
import com.vijay.mall.order.app.quote.CouponStep;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CouponCoinStepsTest {
  private final PromotionService promotions = mock(PromotionService.class);
  private final OrderRepository orders = mock(OrderRepository.class);
  private final UUID user = UUID.randomUUID();

  private QuoteContext ctx(String coupon, CoinRedemption.Mode mode) {
    QuoteContext c = new QuoteContext(user, new QuoteRequest(List.of(), coupon, mode, null, Fulfilment.PICKUP));
    c.lines = List.of(new PricedLine(UUID.randomUUID(), UUID.randomUUID(), "x", 1, 10000, 10000, true, true));
    c.subtotal = 10000;
    return c;
  }

  @Test
  void noCouponCodeDoesNothing() {
    QuoteContext c = ctx(" ", CoinRedemption.Mode.NONE);
    new CouponStep(promotions, orders).apply(c);
    verifyNoInteractions(promotions);
  }

  @Test
  void discountIsCappedAtTheSubtotalAndCoinBlockFollowsTheCoupon() {
    when(promotions.apply(any(), any(), any(), anyLong(), any())).thenReturn(new PromotionService.Applied(99999, false));
    QuoteContext c = ctx("save", CoinRedemption.Mode.ALL);
    new CouponStep(promotions, orders).apply(c);
    assertEquals("SAVE", c.couponCode);
    assertEquals(10000, c.couponDiscount);
    assertTrue(c.couponBlocksCoins);
  }

  @Test
  void couponThatWorksWithCoinsDoesNotBlockThem() {
    when(promotions.apply(any(), any(), any(), anyLong(), any())).thenReturn(new PromotionService.Applied(500, true));
    QuoteContext c = ctx("ok", CoinRedemption.Mode.ALL);
    new CouponStep(promotions, orders).apply(c);
    assertEquals(500, c.couponDiscount);
    assertFalse(c.couponBlocksCoins);
  }

  @Test
  void priorOrdersAreCountedFromTheCustomersActiveOrdersOnlyWhenAsked() {
    when(promotions.apply(any(), any(), any(), anyLong(), any())).thenReturn(new PromotionService.Applied(0, true));
    when(orders.activeOrderCount(user)).thenReturn(2L);
    new CouponStep(promotions, orders).apply(ctx("first", CoinRedemption.Mode.NONE));
    ArgumentCaptor<IntSupplier> prior = ArgumentCaptor.forClass(IntSupplier.class);
    verify(promotions).apply(any(), any(), any(), anyLong(), prior.capture());
    verify(orders, never()).activeOrderCount(any());
    assertEquals(2, prior.getValue().getAsInt());
  }

  @Test
  void aQuoteWithNoUserHasNoPriorOrders() {
    when(promotions.apply(any(), any(), any(), anyLong(), any())).thenReturn(new PromotionService.Applied(0, true));
    QuoteContext c = new QuoteContext(null, new QuoteRequest(List.of(), "first", null, null, Fulfilment.PICKUP));
    c.subtotal = 100;
    new CouponStep(promotions, orders).apply(c);
    ArgumentCaptor<IntSupplier> prior = ArgumentCaptor.forClass(IntSupplier.class);
    verify(promotions).apply(any(), any(), any(), anyLong(), prior.capture());
    assertEquals(0, prior.getValue().getAsInt());
  }

  @Test
  void coinsAreSkippedWithAWarningWhenTheCouponDoesNotAllowThem() {
    WalletService wallet = mock(WalletService.class);
    QuoteContext c = ctx("SAVE", CoinRedemption.Mode.ALL);
    c.couponCode = "SAVE";
    c.couponBlocksCoins = true;
    new CoinRedemptionStep(wallet).apply(c);
    assertEquals(0, c.coinsUsed);
    assertEquals(0, c.coinDiscount);
    assertEquals(List.of("Coins cannot be used together with coupon SAVE"), c.warnings);
    verifyNoInteractions(wallet);
  }

  @Test
  void noCoinModeMeansNoWarningEvenWhenTheCouponBlocksCoins() {
    QuoteContext c = ctx("SAVE", CoinRedemption.Mode.NONE);
    c.couponBlocksCoins = true;
    new CoinRedemptionStep(mock(WalletService.class)).apply(c);
    assertTrue(c.warnings.isEmpty());
  }
}
