package com.vijay.mall.order.app.quote;

import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteStep;
import org.springframework.stereotype.Component;

@Component
public class TotalStep implements QuoteStep {
  @Override public int order() { return 500; }

  @Override
  public void apply(QuoteContext c) {
    c.total = Math.max(0, c.subtotal - c.couponDiscount - c.coinDiscount + c.deliveryFee);
  }
}
