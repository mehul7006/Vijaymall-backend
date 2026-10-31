package com.vijay.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.InvalidSignatureException;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.domain.PaymentGateway;
import com.vijay.mall.payment.infra.RazorpayGateway;
import com.vijay.mall.payment.infra.RazorpaySignature;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Talks to a tiny local HTTP server that plays Razorpay, so the real request/response handling is exercised. */
class RazorpayHttpTest {
  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile String createResponse = "{\"id\":\"order_NEW\"}";
  private volatile String paymentsResponse = "{\"items\":[]}";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " auth=" + ex.getRequestHeaders().getFirst("Authorization")
          + " body=" + body);
      String path = ex.getRequestURI().getPath();
      String out = path.equals("/v1/orders") ? createResponse
          : path.endsWith("/refund") ? "{\"id\":\"rfnd_1\"}"
          : path.endsWith("/payments") ? paymentsResponse : "{}";
      byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() { server.stop(0); }

  private RazorpayGateway gateway(String keyId, String baseUrl) {
    String url = baseUrl != null ? baseUrl : "http://127.0.0.1:" + server.getAddress().getPort();
    return new RazorpayGateway(new AppProps(null, null, null, null,
        new AppProps.Payment("razorpay", 15, keyId, "sec", "whsec", url), null), JsonMapper.builder().build());
  }

  @Test
  void createPostsTheAmountWithBasicAuthAndReturnsTheProviderOrder() {
    var created = gateway("rzp_key", null).create(new PaymentGateway.CreateRequest(UUID.randomUUID(), "VM-5", 125000));
    assertThat(created.providerOrderId()).isEqualTo("order_NEW");
    assertThat(created.session().payload()).containsEntry("razorpayOrderId", "order_NEW").containsEntry("keyId", "rzp_key");
    String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("rzp_key:sec".getBytes(StandardCharsets.UTF_8));
    assertThat(requests).singleElement().satisfies(r -> {
      assertThat(r).startsWith("POST /v1/orders").contains("auth=" + expectedAuth);
      assertThat(r).contains("\"amount\":125000").contains("\"currency\":\"INR\"").contains("\"receipt\":\"VM-5\"");
    });
  }

  @Test
  void createFailsWhenTheProviderAnswersWithoutAnId() {
    createResponse = "{}";
    assertThatThrownBy(() -> gateway("k", null).create(new PaymentGateway.CreateRequest(UUID.randomUUID(), "VM-5", 100)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PAYMENT_PROVIDER_ERROR"));
  }

  @Test
  void withoutKeysEverythingThatNeedsTheProviderIsRefusedClearly() {
    var g = gateway("", null);
    assertThatThrownBy(() -> g.create(new PaymentGateway.CreateRequest(UUID.randomUUID(), "x", 1)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PAYMENT_NOT_CONFIGURED"));
    assertThatThrownBy(() -> g.refund("pay_1", 1, "r")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> g.fetchStatus("o")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> gateway(null, null).fetchStatus("o")).isInstanceOf(ApiException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void refundPostsToThePaymentAndReturnsTheRefundId() {
    assertThat(gateway("k", null).refund("pay_77", 5000, "cancelled")).isEqualTo("rfnd_1");
    assertThat(requests).singleElement().satisfies(r -> assertThat(r).startsWith("POST /v1/payments/pay_77/refund").contains("\"amount\":5000"));
  }

  @Test
  void fetchStatusFindsTheCapturedPayment() {
    paymentsResponse = "{\"items\":[{\"id\":\"pay_a\",\"status\":\"failed\",\"amount\":100},{\"id\":\"pay_b\",\"status\":\"captured\",\"amount\":4200}]}";
    var ev = gateway("k", null).fetchStatus("order_9");
    assertThat(ev).get().isEqualTo(new PaymentEvent.Captured("recon:pay_b", "order_9", "pay_b", 4200));
    assertThat(requests).singleElement().satisfies(r -> assertThat(r).startsWith("GET /v1/orders/order_9/payments"));
  }

  @Test
  void fetchStatusIsEmptyWhenNothingWasCaptured() {
    assertThat(gateway("k", null).fetchStatus("order_9")).isEmpty();
    paymentsResponse = "{\"items\":[{\"id\":\"pay_a\",\"status\":\"created\",\"amount\":100}]}";
    assertThat(gateway("k", null).fetchStatus("order_9")).isEmpty();
  }

  @Test
  void aSignedButUnreadableBodyIsRejected() {
    String body = "not json at all";
    var headers = Map.of("x-razorpay-signature", RazorpaySignature.sign(body, "whsec"));
    assertThatThrownBy(() -> gateway("k", null).parseWebhook(body, headers)).isInstanceOf(InvalidSignatureException.class);
  }

  @Test
  void orderPaidEventCountsAsCaptured() {
    String body = "{\"event\":\"order.paid\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\"o1\",\"amount\":900}}}}";
    var ev = gateway("k", null).parseWebhook(body, Map.of("x-razorpay-signature", RazorpaySignature.sign(body, "whsec"), "x-razorpay-event-id", "e1"));
    assertThat(ev).isEqualTo(new PaymentEvent.Captured("e1", "o1", "pay_1", 900));
  }

  @Test
  void blankBaseUrlFallsBackToTheRealRazorpayHost() {
    assertThat(gateway("k", "").session("o", 1).payload()).containsEntry("razorpayOrderId", "o");
    assertThat(gateway("k", "  ").provider()).isEqualTo("razorpay");
  }

  @Test
  void failedEventWithoutDescriptionGetsADefaultReason() {
    String body = "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"p\",\"order_id\":\"o\"}}}}";
    var ev = gateway("k", null).parseWebhook(body, Map.of("x-razorpay-signature", RazorpaySignature.sign(body, "whsec")));
    assertThat(ev).isInstanceOfSatisfying(PaymentEvent.Failed.class, f -> assertThat(f.reason()).isEqualTo("payment failed"));
  }
}
