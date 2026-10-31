package com.vijay.mall.payment.domain;

/** Provider-neutral result of reading a webhook. Each adapter translates its own format into this. */
public sealed interface PaymentEvent {
  String eventId();

  record Captured(String eventId, String providerOrderId, String providerRef, long amountPaise) implements PaymentEvent {}

  record Failed(String eventId, String providerOrderId, String reason) implements PaymentEvent {}

  record Ignored(String eventId) implements PaymentEvent {}
}
