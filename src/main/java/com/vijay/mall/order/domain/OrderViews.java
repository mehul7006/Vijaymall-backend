package com.vijay.mall.order.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.payment.domain.PaymentSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shapes returned by the order endpoints (see API contract). */
public final class OrderViews {
  private OrderViews() {}

  public record Item(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise) {}

  public record Step(String status, Instant at) {}

  public record Summary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                        Instant placedAt) {}

  public record AdminSummary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                             Instant placedAt, String customerName, String customerEmail, String customerMobile) {}

  public record View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                     String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                     long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                     long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                     List<Step> timeline, Instant placedAt) {}

  public record AdminView(@JsonUnwrapped View order, String customerName, String customerEmail, String customerMobile) {}
}
