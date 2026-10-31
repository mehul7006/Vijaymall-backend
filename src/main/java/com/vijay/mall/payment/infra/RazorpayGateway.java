package com.vijay.mall.payment.infra;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.InvalidSignatureException;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.domain.PaymentGateway;
import com.vijay.mall.payment.domain.PaymentSession;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Razorpay over plain HTTP, so no vendor SDK is needed and the port stays the only dependency. */
@Component
public class RazorpayGateway implements PaymentGateway {
  private final AppProps.Payment cfg;
  private final JsonMapper json;
  private final RestClient http;

  public RazorpayGateway(AppProps props, JsonMapper json) {
    this.cfg = props.payment();
    this.json = json;
    String base = cfg.razorpayBaseUrl() == null || cfg.razorpayBaseUrl().isBlank() ? "https://api.razorpay.com" : cfg.razorpayBaseUrl();
    this.http = RestClient.builder().baseUrl(base).defaultHeaders(h -> {
      if (cfg.razorpayKeyId() != null && !cfg.razorpayKeyId().isBlank()) h.setBasicAuth(cfg.razorpayKeyId(), cfg.razorpayKeySecret());
    }).build();
  }

  @Override public String provider() { return "razorpay"; }

  @Override
  public Created create(CreateRequest r) {
    requireKeys();
    Map<String, Object> body = Map.of("amount", r.amountPaise(), "currency", "INR", "receipt", r.orderNo());
    JsonNode res = http.post().uri("/v1/orders").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    String id = res == null ? null : res.path("id").asString(null);
    if (id == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Could not start payment");
    return new Created(id, session(id, r.amountPaise()));
  }

  @Override
  public PaymentSession session(String providerOrderId, long amountPaise) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("keyId", cfg.razorpayKeyId());
    p.put("razorpayOrderId", providerOrderId);
    p.put("amountPaise", amountPaise);
    p.put("currency", "INR");
    return new PaymentSession("RAZORPAY", p);
  }

  @Override
  public PaymentEvent parseWebhook(String rawBody, Map<String, String> headers) {
    String sig = headers.get("x-razorpay-signature");
    if (!RazorpaySignature.verify(rawBody, sig, cfg.razorpayWebhookSecret())) throw new InvalidSignatureException("bad signature");
    String eventId = headers.getOrDefault("x-razorpay-event-id", "");
    try {
      JsonNode root = json.readTree(rawBody);
      String type = root.path("event").asString("");
      JsonNode pay = root.path("payload").path("payment").path("entity");
      String orderId = pay.path("order_id").asString(null);
      if (eventId.isBlank()) eventId = type + ":" + pay.path("id").asString("");
      return switch (type) {
        case "payment.captured", "order.paid" -> new PaymentEvent.Captured(eventId, orderId, pay.path("id").asString(null), pay.path("amount").asLong());
        case "payment.failed" -> new PaymentEvent.Failed(eventId, orderId, pay.path("error_description").asString("payment failed"));
        default -> new PaymentEvent.Ignored(eventId);
      };
    } catch (Exception e) {
      throw new InvalidSignatureException("unreadable body");
    }
  }

  @Override
  public String refund(String providerRef, long amountPaise, String reason) {
    requireKeys();
    JsonNode res = http.post().uri("/v1/payments/{id}/refund", providerRef).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("amount", amountPaise)).retrieve().body(JsonNode.class);
    return res == null ? null : res.path("id").asString(null);
  }

  @Override
  public Optional<PaymentEvent> fetchStatus(String providerOrderId) {
    requireKeys();
    JsonNode res = http.get().uri("/v1/orders/{id}/payments", providerOrderId).retrieve().body(JsonNode.class);
    if (res == null) return Optional.empty();
    return java.util.stream.StreamSupport.stream(res.path("items").spliterator(), false)
        .filter(p -> "captured".equals(p.path("status").asString()))
        .findFirst()
        .map(p -> (PaymentEvent) new PaymentEvent.Captured("recon:" + p.path("id").asString(), providerOrderId,
            p.path("id").asString(), p.path("amount").asLong()));
  }

  private void requireKeys() {
    if (cfg.razorpayKeyId() == null || cfg.razorpayKeyId().isBlank()) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_NOT_CONFIGURED", "Online payment is not configured");
    }
  }
}
