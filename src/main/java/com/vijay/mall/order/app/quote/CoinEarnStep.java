package com.vijay.mall.order.app.quote;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteStep;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinMath;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Coins are earned on items that earn coins, after coupon and coin discounts; delivery fee earns nothing. */
@Component
public class CoinEarnStep implements QuoteStep {
  private final WalletService wallet;
  private final Clock clock;

  /** Creates the step. */
  public CoinEarnStep(WalletService wallet, Clock clock) {
    this.wallet = wallet;
    this.clock = clock;
  }

  @Override public int order() { return 600; }

  @Override
  public void apply(QuoteContext c) {
    long earning = c.lines.stream().filter(PricedLine::earnsCoins).mapToLong(PricedLine::lineTotalPaise).sum();
    long base = Math.max(0, earning - c.couponDiscount - c.coinDiscount);
    c.coinsToEarn = CoinMath.best(base, wallet.activeRules(clock.instant()));
  }
}
