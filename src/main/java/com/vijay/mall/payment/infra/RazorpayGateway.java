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
    // A slow or hung provider must not hold a request thread for long.
    var timeouts = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    timeouts.setConnectTimeout(java.time.Duration.ofSeconds(5));
    timeouts.setReadTimeout(java.time.Duration.ofSeconds(15));
    this.http = RestClient.builder().baseUrl(base).requestFactory(timeouts).build();
  }

  private com.vijay.mall.config.RuntimeSettings settings;

  /**
   * Optional (plain unit tests run without it): the keys the admin saved in the config screen win over the ones the server was
   * started with, and take effect on the next call, no restart.
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSettings(com.vijay.mall.config.RuntimeSettings settings) { this.settings = settings; }

  private static String nz(String s) { return s == null ? "" : s; }

  private String keyId() { return settings != null ? settings.get(com.vijay.mall.config.Settings.RAZORPAY_KEY_ID) : nz(cfg.razorpayKeyId()); }

  private String keySecret() {
    return settings != null ? settings.get(com.vijay.mall.config.Settings.RAZORPAY_KEY_SECRET) : nz(cfg.razorpayKeySecret());
  }

  private String webhookSecret() {
    return settings != null ? settings.get(com.vijay.mall.config.Settings.RAZORPAY_WEBHOOK_SECRET) : nz(cfg.razorpayWebhookSecret());
  }

  /** Signs the call with the current keys. A key id without a secret is left unauthenticated: configured() already says no. */
  private void auth(org.springframework.http.HttpHeaders h) {
    if (!keyId().isBlank() && !keySecret().isBlank()) h.setBasicAuth(keyId(), keySecret());
  }

  @Override public String provider() { return "razorpay"; }

  @Override
  public boolean configured() { return !keyId().isBlank() && !keySecret().isBlank(); }

  @Override
  public Created create(CreateRequest r) {
    requireKeys();
    Map<String, Object> body = Map.of("amount", r.amountPaise(), "currency", "INR", "receipt", r.orderNo());
    JsonNode res = http.post().uri("/v1/orders").headers(this::auth).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    String id = res == null ? null : res.path("id").asString(null);
    if (id == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Could not start payment");
    return new Created(id, session(id, r.amountPaise()));
  }

  @Override
  public PaymentSession session(String providerOrderId, long amountPaise) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("keyId", keyId());
    p.put("razorpayOrderId", providerOrderId);
    p.put("amountPaise", amountPaise);
    p.put("currency", "INR");
    return new PaymentSession("RAZORPAY", p);
  }

  @Override
  public PaymentEvent parseWebhook(String rawBody, Map<String, String> headers) {
    String sig = headers.get("x-razorpay-signature");
    if (!RazorpaySignature.verify(rawBody, sig, webhookSecret())) throw new InvalidSignatureException("bad signature");
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
    JsonNode res = http.post().uri("/v1/payments/{id}/refund", providerRef).headers(this::auth).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("amount", amountPaise)).retrieve().body(JsonNode.class);
    return res == null ? null : res.path("id").asString(null);
  }

  @Override
  public Optional<PaymentEvent> fetchStatus(String providerOrderId) {
    requireKeys();
    JsonNode res = http.get().uri("/v1/orders/{id}/payments", providerOrderId).headers(this::auth).retrieve().body(JsonNode.class);
    if (res == null) return Optional.empty();
    return java.util.stream.StreamSupport.stream(res.path("items").spliterator(), false)
        .filter(p -> "captured".equals(p.path("status").asString()))
        .findFirst()
        .map(p -> (PaymentEvent) new PaymentEvent.Captured("recon:" + p.path("id").asString(), providerOrderId,
            p.path("id").asString(), p.path("amount").asLong()));
  }

  /** Asks Razorpay for one payment with the saved keys: proves the key id and secret are right. */
  public com.vijay.mall.config.ConnectionTester.Result checkKeys() {
    if (!configured()) return new com.vijay.mall.config.ConnectionTester.Result(false, "Razorpay key id and key secret are not both saved yet.");
    try {
      http.get().uri("/v1/payments?count=1").headers(this::auth).retrieve().body(JsonNode.class);
      boolean live = keyId().startsWith("rzp_live_");
      return new com.vijay.mall.config.ConnectionTester.Result(true, "Razorpay accepted the keys (" + (live ? "LIVE" : "test") + " mode)."
          + (webhookSecret().isBlank() ? " The webhook secret is still missing: payments will not be confirmed instantly." : ""));
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      return new com.vijay.mall.config.ConnectionTester.Result(false, e.getStatusCode().value() == 401
          ? "Razorpay refused the keys (wrong key id or secret)." : "Razorpay answered " + e.getStatusCode().value() + ".");
    } catch (RuntimeException e) {
      return new com.vijay.mall.config.ConnectionTester.Result(false, "Could not reach Razorpay: " + e.getClass().getSimpleName());
    }
  }

  private void requireKeys() {
    if (!configured()) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_NOT_CONFIGURED", "Online payment is not configured");
    }
  }
}
