package com.vijay.mall.order.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** Price breakdown returned to the app. The server is the only place that computes it. */
public record Quote(List<Line> lines, long subtotalPaise, String couponCode, long couponDiscountPaise,
                    int coinsUsed, long coinDiscountPaise, long deliveryFeePaise, long totalPaise,
                    long coinsToEarn, List<String> warnings) {
  @Schema(name = "QuoteLine")
  public record Line(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise,
                     boolean coinRedeemable, boolean earnsCoins, String nameGu, String unitLabel) {
    /** A line without a Gujarati name or pack label; the English name stands in. */
    public Line(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise,
                boolean coinRedeemable, boolean earnsCoins) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, coinRedeemable, earnsCoins, name, "");
    }
  }
}
