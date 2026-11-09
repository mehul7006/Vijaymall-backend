package com.vijay.mall.order.domain;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.util.List;

/**
 * What to price.
 * @param items product ids and quantities
 * @param couponCode optional coupon code
 * @param coinMode how coins are redeemed; {@code null} means none
 * @param percent share of the coins to use; {@code null} means 100
 * @param fulfilment {@code null} means delivery
 */
public record QuoteRequest(List<CatalogService.ItemRequest> items, String couponCode,
                           CoinRedemption.Mode coinMode, Integer percent, Fulfilment fulfilment) {
  /** The coin mode, {@code NONE} when not given. */
  public CoinRedemption.Mode mode() { return coinMode == null ? CoinRedemption.Mode.NONE : coinMode; }
  /** The coin percentage, 100 when not given. */
  public int pct() { return percent == null ? 100 : percent; }
  /** The fulfilment, delivery when not given. */
  public Fulfilment fulfil() { return fulfilment == null ? Fulfilment.DELIVERY : fulfilment; }
}
