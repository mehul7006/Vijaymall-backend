package com.vijay.mall.payment.domain;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port every payment provider implements. The rest of the app only knows this interface. */
public interface PaymentGateway {
  record CreateRequest(UUID orderId, String orderNo, long amountPaise) {}

  record Created(String providerOrderId, PaymentSession session) {}

  /** False when the provider's keys are not set, so the method cannot take a payment yet. Cash-like providers need none. */
  default boolean configured() { return true; }

  /** Short lowercase name used in URLs and config, e.g. "razorpay". */
  String provider();

  Created create(CreateRequest request);

  /** Rebuilds the app payload for an order that was already created at the provider. */
  PaymentSession session(String providerOrderId, long amountPaise);

  /** Verifies the signature and reads the event. Throws InvalidSignatureException when it does not match. */
  PaymentEvent parseWebhook(String rawBody, Map<String, String> headers);

  /** Returns the provider's refund reference. */
  String refund(String providerRef, long amountPaise, String reason);

  /**
   * Same, but tells the provider which of our refund rows this is, so a refund that went through while its answer was lost can
   * be found again (see {@link #findRefund}). Providers that cannot do this just use the plain call.
   */
  default String refund(String providerRef, long amountPaise, String reason, String ourRefundId) { return refund(providerRef, amountPaise, reason); }

  /** The provider's reference of a refund we sent earlier for {@code ourRefundId}, if it has one: stops a retry from refunding twice. */
  default Optional<String> findRefund(String providerRef, String ourRefundId) { return Optional.empty(); }

  /** Asks the provider what happened, for orders whose webhook never arrived. */
  Optional<PaymentEvent> fetchStatus(String providerOrderId);
}
