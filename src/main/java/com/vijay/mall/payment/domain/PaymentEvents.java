package com.vijay.mall.payment.domain;

import java.util.UUID;

/** Spring events the payment module publishes; the order module listens. Keeps the two modules apart. */
public final class PaymentEvents {
  private PaymentEvents() {}

  /**
   * The right amount was received for an open payment, so the order can move on.
   * @param providerRef the provider's payment id
   */
  public record PaymentCaptured(UUID orderId, String providerRef) {}

  /**
   * Money arrived that cannot pay the order: kind MISMATCH (wrong amount) or DUPLICATE (the order is already paid). It is
   * being refunded in full; the order module tells the customer and the staff.
   */
  public record PaymentAnomaly(UUID orderId, String kind, long expectedPaise, long receivedPaise) {}

  /**
   * Published when a payment is reported as failed so the order module can react.
   * @param reason the provider's description
   */
  public record PaymentFailed(UUID orderId, String reason) {}
}
