package com.vijay.mall.order.domain;

import com.vijay.mall.catalog.domain.PricedLine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Mutable state passed along the quote steps. Each step fills in its own part. */
public final class QuoteContext {
  public final UUID userId;
  public final QuoteRequest request;
  public List<PricedLine> lines = List.of();
  public long subtotal;
  public String couponCode;
  public long couponDiscount;
  public int coinsUsed;
  public long coinDiscount;
  public long deliveryFee;
  public long total;
  public long coinsToEarn;
  /** When set, these lines are used as they are (counter sales price their own lines). */
  public final List<PricedLine> preset;
  public final List<String> warnings = new ArrayList<>();

  public QuoteContext(UUID userId, QuoteRequest request) { this(userId, request, null); }

  public QuoteContext(UUID userId, QuoteRequest request, List<PricedLine> preset) {
    this.userId = userId;
    this.request = request;
    this.preset = preset;
  }

  public Quote toQuote() {
    var out = lines.stream().map(l -> new Quote.Line(l.productId(), l.name(), l.qty(), l.unitPricePaise(),
        l.lineTotalPaise(), l.coinRedeemable(), l.earnsCoins())).toList();
    return new Quote(out, subtotal, couponCode, couponDiscount, coinsUsed, coinDiscount, deliveryFee, total,
        coinsToEarn, List.copyOf(warnings));
  }
}
