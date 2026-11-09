package com.vijay.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.PaymentGateway;
import com.vijay.mall.payment.infra.RazorpayGateway;
import com.vijay.mall.payment.infra.RazorpaySignature;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Razorpay edge cases: configuration check, empty provider answers, and signature helper corner cases. */
class RazorpayCoverageTest {
  private HttpServer server;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // answers 200 with no body at all
    server.createContext("/", ex -> {
      ex.getRequestBody().readAllBytes();
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() { server.stop(0); }

  private RazorpayGateway gateway(String keyId, String secret, String baseUrl) {
    return new RazorpayGateway(new AppProps(null, null, null, null,
        new AppProps.Payment("razorpay", 15, keyId, secret, "whsec", baseUrl), null), JsonMapper.builder().build());
  }

  private String local() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

  @Test
  void configuredNeedsBothKeyIdAndSecret() {
    assertThat(gateway("id", "sec", null).configured()).isTrue();
    assertThat(gateway(null, "sec", null).configured()).isFalse();
    assertThat(gateway("", "sec", null).configured()).isFalse();
    assertThat(gateway("  ", "sec", null).configured()).isFalse();
    assertThat(gateway("id", "", null).configured()).isFalse();
    assertThat(gateway("id", "  ", null).configured()).isFalse();
  }

  @Test
  void aKeyIdWithoutASecretStartsButReportsNotConfigured() {
    // Used to crash the whole app at start-up with a generic IllegalArgumentException.
    assertThat(gateway("id", null, null).configured()).isFalse();
  }

  @Test
  void aMissingBaseUrlStillBuildsAGateway() {
    assertThat(gateway("id", "sec", null).provider()).isEqualTo("razorpay");
  }

  @Test
  void createFailsClearlyWhenTheProviderReturnsNothing() {
    assertThatThrownBy(() -> gateway("id", "sec", local()).create(new PaymentGateway.CreateRequest(UUID.randomUUID(), "VM-1", 100)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PAYMENT_PROVIDER_ERROR"));
  }

  @Test
  void refundWithAnEmptyAnswerHasNoReference() {
    assertThat(gateway("id", "sec", local()).refund("pay_1", 100, "r")).isNull();
  }

  @Test
  void fetchStatusWithAnEmptyAnswerFindsNothing() {
    Optional<?> ev = gateway("id", "sec", local()).fetchStatus("order_1");
    assertThat(ev).isEmpty();
  }

  @Test
  void signingWithoutASecretIsAnInternalError() {
    assertThatThrownBy(() -> RazorpaySignature.sign("body", null)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void verifyRejectsABlankOrMissingSecretAndTrimsTheSignature() {
    String sig = RazorpaySignature.sign("body", "s");
    assertThat(RazorpaySignature.verify("body", sig, null)).isFalse();
    assertThat(RazorpaySignature.verify("body", sig, "   ")).isFalse();
    assertThat(RazorpaySignature.verify("body", "  " + sig + "\n", "s")).isTrue();
    assertThat(RazorpaySignature.verify("body", sig.toUpperCase(), "s")).as("hex compare is exact").isFalse();
  }
}
