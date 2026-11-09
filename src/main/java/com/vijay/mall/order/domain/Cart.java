package com.vijay.mall.order.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** A customer's saved basket. Lines are pack ids, exactly what POST /cart/quote accepts. */
@Schema(name = "Basket")
public record Cart(List<Line> items, String couponCode, CoinRedemption.Mode coinMode, Integer percent, Instant updatedAt) {
  public static final int MAX_LINES = 100;
  /** The most units of one product in a basket; the same constant the quote and order calls use. */
  public static final int MAX_QTY = com.vijay.mall.common.OrderLimits.MAX_LINE_QTY;

  @Schema(name = "BasketLine")

  public record Line(UUID productId, int qty) {}

  /** What a customer without a saved basket gets back: 200 with no lines, never 404. */
  public static Cart empty() { return new Cart(List.of(), null, CoinRedemption.Mode.NONE, null, null); }

  /**
   * Cleans what the client sent: repeated products are merged (first position wins), a blank coupon becomes null and
   * is upper-cased, a missing coin mode becomes NONE, and percent is kept only for PERCENT.
   */
  public static Cart normalized(List<Line> lines, String coupon, CoinRedemption.Mode mode, Integer percent) {
    var merged = new LinkedHashMap<UUID, Integer>();
    if (lines != null) lines.forEach(l -> merged.merge(l.productId(), l.qty(), Integer::sum));
    var items = merged.entrySet().stream().map(e -> new Line(e.getKey(), Math.min(e.getValue(), MAX_QTY))).toList();
    String code = coupon == null || coupon.isBlank() ? null : coupon.trim().toUpperCase();
    var m = mode == null ? CoinRedemption.Mode.NONE : mode;
    Integer pct = m == CoinRedemption.Mode.PERCENT ? Math.max(0, Math.min(percent == null ? 0 : percent, 100)) : null;
    return new Cart(items, code, m, pct, null);
  }
}
