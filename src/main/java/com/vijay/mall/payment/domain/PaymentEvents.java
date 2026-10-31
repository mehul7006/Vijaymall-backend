package com.vijay.mall.payment.domain;

import java.util.UUID;

/** Spring events the payment module publishes; the order module listens. Keeps the two modules apart. */
public final class PaymentEvents {
  private PaymentEvents() {}

  public record PaymentCaptured(UUID orderId, String providerRef) {}

  public record PaymentFailed(UUID orderId, String reason) {}
}
