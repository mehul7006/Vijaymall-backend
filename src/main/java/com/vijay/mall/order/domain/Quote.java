package com.vijay.mall.order.domain;

import java.util.List;
import java.util.UUID;

/** Price breakdown returned to the app. The server is the only place that computes it. */
public record Quote(List<Line> lines, long subtotalPaise, String couponCode, long couponDiscountPaise,
                    int coinsUsed, long coinDiscountPaise, long deliveryFeePaise, long totalPaise,
                    long coinsToEarn, List<String> warnings) {
  public record Line(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise,
                     boolean coinRedeemable, boolean earnsCoins) {}
}
