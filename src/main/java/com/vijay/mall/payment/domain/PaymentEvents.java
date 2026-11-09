package com.vijay.mall.payment.domain;

import java.util.UUID;

/** Spring events the payment module publishes; the order module listens. Keeps the two modules apart. */
public final class PaymentEvents {
  private PaymentEvents() {}

  public record PaymentCaptured(UUID orderId, String providerRef) {}

  /**
   * Money arrived that cannot pay the order: kind MISMATCH (wrong amount) or DUPLICATE (the order is already paid). It is
   * being refunded in full; the order module tells the customer and the staff.
   */
  public record PaymentAnomaly(UUID orderId, String kind, long expectedPaise, long receivedPaise) {}

  public record PaymentFailed(UUID orderId, String reason) {}
}
