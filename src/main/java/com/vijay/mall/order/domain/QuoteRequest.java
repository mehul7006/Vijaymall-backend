package com.vijay.mall.order.domain;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.util.List;

public record QuoteRequest(List<CatalogService.ItemRequest> items, String couponCode,
                           CoinRedemption.Mode coinMode, Integer percent, Fulfilment fulfilment) {
  public CoinRedemption.Mode mode() { return coinMode == null ? CoinRedemption.Mode.NONE : coinMode; }
  public int pct() { return percent == null ? 100 : percent; }
  public Fulfilment fulfil() { return fulfilment == null ? Fulfilment.DELIVERY : fulfilment; }
}
