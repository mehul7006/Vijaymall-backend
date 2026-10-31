package com.vijay.mall.order.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.order.app.quote.DeliveryFeeStep;
import com.vijay.mall.order.app.quote.TotalStep;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuoteStepsTest {
  private static AppProps props() {
    return new AppProps(null, null, null, null, null, new AppProps.Delivery(4000, 49900));
  }

  private static QuoteContext ctx(Fulfilment f, long subtotal) {
    QuoteContext c = new QuoteContext(UUID.randomUUID(), new QuoteRequest(List.of(), null, null, null, f));
    c.lines = List.of(new PricedLine(UUID.randomUUID(), UUID.randomUUID(), "x", 1, subtotal, subtotal, true, true));
    c.subtotal = subtotal;
    return c;
  }

  @Test
  void deliveryFeeAppliesBelowTheFreeLimit() {
    QuoteContext c = ctx(Fulfilment.DELIVERY, 30000);
    new DeliveryFeeStep(props()).apply(c);
    assertEquals(4000, c.deliveryFee);
  }

  @Test
  void deliveryIsFreeAtTheLimitAfterDiscounts() {
    QuoteContext c = ctx(Fulfilment.DELIVERY, 52000);
    c.couponDiscount = 2100; // 49900 left, exactly the limit
    new DeliveryFeeStep(props()).apply(c);
    assertEquals(0, c.deliveryFee);
  }

  @Test
  void pickupHasNoFee() {
    QuoteContext c = ctx(Fulfilment.PICKUP, 100);
    new DeliveryFeeStep(props()).apply(c);
    assertEquals(0, c.deliveryFee);
  }

  @Test
  void totalIsSubtotalMinusDiscountsPlusFeeAndNeverNegative() {
    QuoteContext c = ctx(Fulfilment.DELIVERY, 10000);
    c.couponDiscount = 3000;
    c.coinDiscount = 2000;
    c.deliveryFee = 4000;
    new TotalStep().apply(c);
    assertEquals(9000, c.total);
    c.couponDiscount = 20000;
    new TotalStep().apply(c);
    assertEquals(0, c.total);
  }
}
