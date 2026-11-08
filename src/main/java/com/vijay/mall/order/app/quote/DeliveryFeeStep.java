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

  private com.vijay.mall.config.RuntimeSettings settings;

  /** Optional (plain unit tests run without it): the admin can change the delivery fee from the config screen. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSettings(com.vijay.mall.config.RuntimeSettings settings) { this.settings = settings; }

  @Override public int order() { return 400; }

  @Override
  public void apply(QuoteContext c) {
    if (c.request.fulfil() == Fulfilment.PICKUP) return;
    long afterDiscounts = c.subtotal - c.couponDiscount - c.coinDiscount;
    long freeAbove = settings != null ? settings.getLong(com.vijay.mall.config.Settings.DELIVERY_FREE_ABOVE) : cfg.freeAbovePaise();
    long fee = settings != null ? settings.getLong(com.vijay.mall.config.Settings.DELIVERY_FEE) : cfg.feePaise();
    c.deliveryFee = afterDiscounts >= freeAbove ? 0 : fee;
  }
}
