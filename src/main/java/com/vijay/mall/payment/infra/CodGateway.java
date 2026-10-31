package com.vijay.mall.payment.infra;

import com.vijay.mall.payment.domain.InvalidSignatureException;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.domain.PaymentGateway;
import com.vijay.mall.payment.domain.PaymentSession;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Cash on delivery: nothing to start, nothing to verify, nothing to refund online. */
@Component
public class CodGateway implements PaymentGateway {
  @Override public String provider() { return "cod"; }

  @Override public Created create(CreateRequest r) { return new Created(null, null); }

  @Override public PaymentSession session(String providerOrderId, long amountPaise) { return null; }

  @Override
  public PaymentEvent parseWebhook(String rawBody, Map<String, String> headers) {
    throw new InvalidSignatureException("cod has no webhook");
  }

  @Override public String refund(String providerRef, long amountPaise, String reason) { return null; }

  @Override public Optional<PaymentEvent> fetchStatus(String providerOrderId) { return Optional.empty(); }
}
