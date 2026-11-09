package com.vijay.mall.order.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.payment.domain.PaymentSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shapes returned by the order endpoints (see API contract). */
public final class OrderViews {
  private OrderViews() {}

  /** One order line as it was at order time, so it still reads right after the product is edited. */
  @Schema(name = "OrderItem")
  public record Item(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise, String nameGu,
                     String unitLabel, long couponDiscountPaise) {
    /** couponDiscountPaise is this line's share of the order's coupon discount. */
    public Item(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise, String nameGu,
                String unitLabel) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, nameGu, unitLabel, 0);
    }

    public Item(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, name, "");
    }
  }

  public record Step(String status, Instant at) {}

  @Schema(name = "OrderSummary")

  public record Summary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                        Instant placedAt, long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                        long coinDiscountPaise, long deliveryFeePaise, String paymentMethod, String paymentStatus) {}

  /** Same money fields as {@link Summary} (and as {@link View}), so the customer and the shop always read the same numbers. */
  public record AdminSummary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                             Instant placedAt, String customerName, String customerEmail, String customerMobile,
                             long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                             long coinDiscountPaise, long deliveryFeePaise, String paymentMethod, String paymentStatus) {}

  public record View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                     String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                     long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                     long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                     List<Step> timeline, Instant placedAt, String slot, String cancelReason, Integer coinBalance,
                     Instant paymentExpiresAt) {
    /** Without the time the customer has left to pay (only set while an online order waits for payment). */
    public View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                List<Step> timeline, Instant placedAt, String slot, String cancelReason, Integer coinBalance) {
      this(id, orderNo, status, fulfilment, paymentMethod, paymentStatus, items, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsEarned, address,
          paymentSession, timeline, placedAt, slot, cancelReason, coinBalance, null);
    }

    /** Without the customer's current coin balance. */
    public View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                List<Step> timeline, Instant placedAt, String slot, String cancelReason) {
      this(id, orderNo, status, fulfilment, paymentMethod, paymentStatus, items, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsEarned, address,
          paymentSession, timeline, placedAt, slot, cancelReason, null);
    }

    public View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                List<Step> timeline, Instant placedAt) {
      this(id, orderNo, status, fulfilment, paymentMethod, paymentStatus, items, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsEarned, address,
          paymentSession, timeline, placedAt, null, null, null);
    }
  }

  public record AdminView(@JsonUnwrapped View order, String customerName, String customerEmail, String customerMobile) {}
}
