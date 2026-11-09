package com.vijay.mall.order.app;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.order.domain.QuoteStep;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs every QuoteStep in order. Used by the quote endpoint and again when placing an order. */
@Service
public class QuoteService {
  private final List<QuoteStep> steps;

  /** Creates the service; the steps are sorted by their {@code order()}. */
  public QuoteService(List<QuoteStep> steps) {
    this.steps = steps.stream().sorted(Comparator.comparingInt(QuoteStep::order)).toList();
  }

  /** Quote for lines the caller already priced (counter sales). Never charges delivery. */
  @Transactional(readOnly = true)
  public Quote quoteLines(UUID userId, List<PricedLine> lines, String couponCode,
                          CoinRedemption.Mode mode, Integer percent) {
    var req = new QuoteRequest(List.of(), couponCode, mode, percent, Fulfilment.PICKUP);
    QuoteContext ctx = new QuoteContext(userId, req, lines);
    for (QuoteStep s : steps) s.apply(ctx);
    return ctx.toQuote();
  }

  /** Prices an online basket by running every step: subtotal, coupon, coins, delivery fee, total, coins to earn. */
  @Transactional(readOnly = true)
  public Quote quote(UUID userId, QuoteRequest req) {
    QuoteContext ctx = new QuoteContext(userId, req);
    for (QuoteStep s : steps) s.apply(ctx);
    return ctx.toQuote();
  }
}
