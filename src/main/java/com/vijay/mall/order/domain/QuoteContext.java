package com.vijay.mall.order.domain;

import com.vijay.mall.catalog.domain.PricedLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mutable state passed along the quote steps. Each step fills in its own part. */
public final class QuoteContext {
  public final UUID userId;
  public final QuoteRequest request;
  public List<PricedLine> lines = List.of();
  public long subtotal;
  public String couponCode;
  public long couponDiscount;
  /** The coupon discount's share per line, in the order of {@link #lines}; empty when there is no split. */
  public List<Long> couponByLine = List.of();
  public int coinsUsed;
  public long coinDiscount;
  public long deliveryFee;
  public long total;
  public long coinsToEarn;
  /** Set by the coupon step when the coupon cannot be combined with coins; the coin step then skips redemption. */
  public boolean couponBlocksCoins;
  /** When set, these lines are used as they are (counter sales price their own lines). */
  public final List<PricedLine> preset;
  public final List<String> warnings = new ArrayList<>();

  public QuoteContext(UUID userId, QuoteRequest request) { this(userId, request, null); }

  public QuoteContext(UUID userId, QuoteRequest request, List<PricedLine> preset) {
    this.userId = userId;
    this.request = request;
    this.preset = preset;
  }

  /** The coupon share of line i, or 0 when the coupon step gave no split. */
  private long couponShare(int i) { return couponByLine.size() == lines.size() ? couponByLine.get(i) : 0; }

  public Quote toQuote() {
    List<Quote.Line> out = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      PricedLine l = lines.get(i);
      out.add(new Quote.Line(l.productId(), l.name(), l.qty(), l.unitPricePaise(), l.lineTotalPaise(), l.coinRedeemable(),
          l.earnsCoins(), l.nameGu(), l.unitLabel(), l.categoryId(), couponShare(i), l.flashSaleId(),
          l.flashSaleId() == null ? l.unitPricePaise() : l.listUnitPricePaise(),
          l.flashSaleId() == null ? 0 : (l.listUnitPricePaise() - l.unitPricePaise()) * l.qty()));
    }
    return new Quote(out, subtotal, couponCode, couponDiscount, coinsUsed, coinDiscount, deliveryFee, total,
        coinsToEarn, List.copyOf(warnings), couponByCategory());
  }

  /** Per category of the cart: what it costs and how much of the coupon discount it received. Empty without a coupon discount. */
  private List<Quote.CategoryDiscount> couponByCategory() {
    if (couponCode == null || couponDiscount <= 0) return List.of();
    Map<UUID, long[]> byCategory = new LinkedHashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      PricedLine l = lines.get(i);
      if (l.categoryId() == null) continue;
      long[] sums = byCategory.computeIfAbsent(l.categoryId(), k -> new long[2]);
      sums[0] += l.lineTotalPaise();
      sums[1] += couponShare(i);
    }
    return byCategory.entrySet().stream()
        .map(e -> new Quote.CategoryDiscount(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
  }
}
