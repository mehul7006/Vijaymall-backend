package com.vijay.mall.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.vijay.mall.payment.domain.InvalidSignatureException;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.domain.PaymentGateway;
import com.vijay.mall.payment.domain.PaymentSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A second payment provider used only in tests. It proves the app works with any adapter that implements the port:
 * webhook body {"eventId","type":"captured|failed","providerOrderId","amount"}, header x-fake-signature: ok.
 */
public class FakeGateway implements PaymentGateway {
  public final List<String> refunds = new CopyOnWriteArrayList<>();
  private final JsonMapper json = JsonMapper.builder().build();

  @Override public String provider() { return "fake"; }

  @Override
  public Created create(CreateRequest r) {
    String id = "fake_" + r.orderId();
    return new Created(id, session(id, r.amountPaise()));
  }

  @Override
  public PaymentSession session(String providerOrderId, long amountPaise) {
    return new PaymentSession("WEB", Map.of("url", "https://pay.test/" + providerOrderId, "amountPaise", amountPaise));
  }

  @Override
  public PaymentEvent parseWebhook(String rawBody, Map<String, String> headers) {
    if (!"ok".equals(headers.get("x-fake-signature"))) throw new InvalidSignatureException("bad");
    try {
      JsonNode n = json.readTree(rawBody);
      String id = n.path("eventId").asString();
      return switch (n.path("type").asString()) {
        case "captured" -> new PaymentEvent.Captured(id, n.path("providerOrderId").asString(), "pay_" + id, n.path("amount").asLong());
        case "failed" -> new PaymentEvent.Failed(id, n.path("providerOrderId").asString(), "declined");
        default -> new PaymentEvent.Ignored(id);
      };
    } catch (Exception e) {
      throw new InvalidSignatureException("unreadable");
    }
  }

  @Override
  public String refund(String providerRef, long amountPaise, String reason) {
    refunds.add(providerRef + ":" + amountPaise);
    return "refund_" + providerRef;
  }

  @Override public Optional<PaymentEvent> fetchStatus(String providerOrderId) { return Optional.empty(); }
}
