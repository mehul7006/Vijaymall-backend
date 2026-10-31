package com.vijay.mall.order.app.quote;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteStep;
import org.springframework.stereotype.Component;

@Component
public class DeliveryFeeStep implements QuoteStep {
  private final AppProps.Delivery cfg;

  public DeliveryFeeStep(AppProps props) { this.cfg = props.delivery(); }

  @Override public int order() { return 400; }

  @Override
  public void apply(QuoteContext c) {
    if (c.request.fulfil() == Fulfilment.PICKUP) return;
    long afterDiscounts = c.subtotal - c.couponDiscount - c.coinDiscount;
    c.deliveryFee = afterDiscounts >= cfg.freeAbovePaise() ? 0 : cfg.feePaise();
  }
}
