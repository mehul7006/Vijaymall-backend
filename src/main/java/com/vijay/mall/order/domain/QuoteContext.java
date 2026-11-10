package com.vijay.mall.order.domain;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.tax.GstRates;
import com.vijay.mall.common.tax.GstSlab;
import com.vijay.mall.common.tax.GstSlabs;
import com.vijay.mall.common.tax.TaxSplit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mutable state passed along the quote steps. Each step fills in its own part. */
public final class QuoteContext {
  /** The customer being quoted; may be {@code null} for an anonymous counter sale. */
  public final UUID userId;
  /** What was asked for. */
  public final QuoteRequest request;
  /** The priced lines, set by the subtotal step. */
  public List<PricedLine> lines = List.of();
  /** Sum of the line totals, in paise. */
  public long subtotal;
  /** The normalised coupon code, if one was given. */
  public String couponCode;
  /** What the coupon takes off, in paise. */
  public long couponDiscount;
  /** The coupon discount's share per line, in the order of {@link #lines}; empty when there is no split. */
  public List<Long> couponByLine = List.of();
  /** The minimum order of the coupon that was applied (0 when none), kept on the order for the return policy. */
  public long couponMinOrder;
  /** Coins redeemed. */
  public int coinsUsed;
  /** What the coins take off, in paise. */
  public long coinDiscount;
  /** Delivery fee, in paise. */
  public long deliveryFee;
  /** Amount to pay, in paise. */
  public long total;
  /** Coins the customer will earn on this order. */
  public long coinsToEarn;
  /** Set by the coupon step when the coupon cannot be combined with coins; the coin step then skips redemption. */
  public boolean couponBlocksCoins;
  /** When set, these lines are used as they are (counter sales price their own lines). */
  public final List<PricedLine> preset;
  /** The GST of each line in the order of {@link #lines}, set by the GST step; empty for counter quotes, which price their own GST. */
  public List<LineGst> gstLines = List.of();
  /** Messages for the customer about anything that could not be applied. */
  public final List<String> warnings = new ArrayList<>();

  /**
   * The GST of one line: its rate and HSN, whether the shop ever saved a rate for the product, and the split of what the customer pays for
   * the line (after its coupon and coin share).
   */
  public record LineGst(BigDecimal gstPercent, String hsn, boolean configured, TaxSplit split) {}

  /** A context whose lines come from the catalog. */
  public QuoteContext(UUID userId, QuoteRequest request) { this(userId, request, null); }

  /**
   * A context that uses {@code preset} lines instead of pricing them (counter sales); {@code null} for normal pricing.
   */
  public QuoteContext(UUID userId, QuoteRequest request, List<PricedLine> preset) {
    this.userId = userId;
    this.request = request;
    this.preset = preset;
  }

  /** The coupon share of line i, or 0 when the coupon step gave no split. */
  private long couponShare(int i) { return couponByLine.size() == lines.size() ? couponByLine.get(i) : 0; }

  /** The coupon share of every line, or {@code null} when the coupon step gave no split. */
  public long[] couponShares() {
    return couponByLine.size() == lines.size() ? couponByLine.stream().mapToLong(Long::longValue).toArray() : null;
  }

  /**
   * Freezes the context into the immutable {@link Quote} returned to the app, including each line's coupon share and the per-category coupon breakdown.
   */
  public Quote toQuote() {
    List<Quote.Line> out = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      PricedLine l = lines.get(i);
      LineGst g = gstLines.size() == lines.size() ? gstLines.get(i) : null;
      out.add(new Quote.Line(l.productId(), l.name(), l.qty(), l.unitPricePaise(), l.lineTotalPaise(), l.coinRedeemable(),
          l.earnsCoins(), l.nameGu(), l.unitLabel(), l.categoryId(), couponShare(i), l.flashSaleId(),
          l.flashSaleId() == null ? l.unitPricePaise() : l.listUnitPricePaise(),
          l.flashSaleId() == null ? 0 : (l.listUnitPricePaise() - l.unitPricePaise()) * l.qty(),
          g == null ? GstRates.ZERO : g.gstPercent(), g == null ? "" : g.hsn(), g == null ? 0 : g.split().taxable(),
          g == null ? 0 : g.split().cgst(), g == null ? 0 : g.split().sgst(), g != null && g.configured()));
    }
    List<GstSlab> slabs = GstSlabs.of(gstLines.stream().map(LineGst::gstPercent).toList(), gstLines.stream().map(LineGst::split).toList());
    TaxSplit tax = GstSlabs.total(slabs);
    return new Quote(out, subtotal, couponCode, couponDiscount, coinsUsed, coinDiscount, deliveryFee, total,
        coinsToEarn, List.copyOf(warnings), couponByCategory(), couponMinOrder, tax.taxable(), tax.cgst(), tax.sgst(), tax.tax(), slabs);
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
