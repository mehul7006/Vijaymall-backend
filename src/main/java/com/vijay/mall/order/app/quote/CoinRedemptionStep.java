package com.vijay.mall.order.app.quote;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteStep;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinRedemption;
import com.vijay.mall.wallet.domain.CoinSettings;
import org.springframework.stereotype.Component;

@Component
public class CoinRedemptionStep implements QuoteStep {
  private final WalletService wallet;

  public CoinRedemptionStep(WalletService wallet) { this.wallet = wallet; }

  @Override public int order() { return 300; }

  @Override
  public void apply(QuoteContext c) {
    if (c.request.mode() == CoinRedemption.Mode.NONE) return;
    CoinSettings s = wallet.settings();
    long redeemable = c.lines.stream().filter(PricedLine::coinRedeemable).mapToLong(PricedLine::lineTotalPaise).sum();
    long base = Math.min(redeemable, c.subtotal - c.couponDiscount);
    int coins = CoinRedemption.coinsToUse(base, c.request.mode(), c.request.pct(), wallet.balance(c.userId), s);
    c.coinsUsed = coins;
    c.coinDiscount = coins * s.paisePerCoin();
    if (redeemable < c.subtotal && coins > 0) c.warnings.add("Some items cannot be paid with coins");
  }
}
