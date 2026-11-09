package com.vijay.mall.order.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Price breakdown returned to the app. The server is the only place that computes it.
 * couponMinOrderPaise (not sent to the app) is the minimum order of the coupon, kept on the order for the return policy.
 * couponByCategory lists, for a cart with a coupon, what the coupon took off each category in the cart (a category the
 * coupon does not cover shows 0), so the app can show the discount per category.
 */
public record Quote(List<Line> lines, long subtotalPaise, String couponCode, long couponDiscountPaise,
                    int coinsUsed, long coinDiscountPaise, long deliveryFeePaise, long totalPaise,
                    long coinsToEarn, List<String> warnings, List<CategoryDiscount> couponByCategory,
                    @JsonIgnore long couponMinOrderPaise) {

  /** A quote that does not say what minimum order its coupon had. */
  public Quote(List<Line> lines, long subtotalPaise, String couponCode, long couponDiscountPaise,
               int coinsUsed, long coinDiscountPaise, long deliveryFeePaise, long totalPaise,
               long coinsToEarn, List<String> warnings, List<CategoryDiscount> couponByCategory) {
    this(lines, subtotalPaise, couponCode, couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise,
        totalPaise, coinsToEarn, warnings, couponByCategory, 0);
  }

  /** A quote with no per-category breakdown. */
  public Quote(List<Line> lines, long subtotalPaise, String couponCode, long couponDiscountPaise,
               int coinsUsed, long coinDiscountPaise, long deliveryFeePaise, long totalPaise,
               long coinsToEarn, List<String> warnings) {
    this(lines, subtotalPaise, couponCode, couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise,
        totalPaise, coinsToEarn, warnings, List.of());
  }

  /** The coupon discount that landed on one category of the cart. */
  public record CategoryDiscount(UUID categoryId, long subtotalPaise, long discountPaise) {}

  /** couponDiscountPaise is this line's share of the coupon discount (0 when the coupon does not cover its category). */
  @Schema(name = "QuoteLine")
  public record Line(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise,
                     boolean coinRedeemable, boolean earnsCoins, String nameGu, String unitLabel, UUID categoryId,
                     long couponDiscountPaise, UUID flashSaleId, long listPricePaise, long flashDiscountPaise) {
    /**
     * A line that is not on a flash sale. For a flash sale line, flashSaleId is set, listPricePaise is the normal unit price and
     * flashDiscountPaise what the sale takes off the whole line; otherwise listPricePaise equals unitPricePaise and the discount is 0.
     */
    public Line(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise,
                boolean coinRedeemable, boolean earnsCoins, String nameGu, String unitLabel, UUID categoryId,
                long couponDiscountPaise) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, coinRedeemable, earnsCoins, nameGu, unitLabel, categoryId,
          couponDiscountPaise, null, unitPricePaise, 0);
    }
    /** A line with no category or coupon share. */
    public Line(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise,
                boolean coinRedeemable, boolean earnsCoins, String nameGu, String unitLabel) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, coinRedeemable, earnsCoins, nameGu, unitLabel, null, 0);
    }

    /** A line without a Gujarati name or pack label; the English name stands in. */
    public Line(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise,
                boolean coinRedeemable, boolean earnsCoins) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, coinRedeemable, earnsCoins, name, "");
    }
  }
}
