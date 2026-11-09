package com.vijay.mall.payment.domain;

/** Provider-neutral result of reading a webhook. Each adapter translates its own format into this. */
public sealed interface PaymentEvent {
  /** The provider's id of the event, used to ignore a repeated delivery. */
  String eventId();

  /**
   * Money was received.
   * @param providerOrderId the provider's order id, which identifies our payment
   * @param providerRef the provider's payment id, needed to refund
   * @param amountPaise the amount received, in paise; compared with what the order expected
   */
  record Captured(String eventId, String providerOrderId, String providerRef, long amountPaise) implements PaymentEvent {}

  /**
   * A payment attempt failed. The customer may try again on the same provider order.
   * @param reason the provider's description of the failure
   */
  record Failed(String eventId, String providerOrderId, String reason) implements PaymentEvent {}

  /** An event type the shop does not act on. */
  record Ignored(String eventId) implements PaymentEvent {}
}
