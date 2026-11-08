package com.vijay.mall.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The "test these keys" probe used by the config screen, against a local stand-in for Razorpay. */
class RazorpayCheckKeysTest {
  private HttpServer server;
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> authHeader = new AtomicReference<>("none");
  private RuntimeSettings settings;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      authHeader.set(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
      byte[] body = "{}".getBytes();
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(status.get(), body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    settings = mock(RuntimeSettings.class);
  }

  @AfterEach
  void stop() { server.stop(0); }

  private RazorpayGateway gateway(String base) {
    RazorpayGateway g = new RazorpayGateway(new AppProps(null, null, null, null,
        new AppProps.Payment("razorpay", 15, "", "", "", base), null), JsonMapper.builder().build());
    g.setSettings(settings);
    return g;
  }

  private String local() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

  private void keys(String id, String secret, String webhook) {
    when(settings.get(Settings.RAZORPAY_KEY_ID)).thenReturn(id);
    when(settings.get(Settings.RAZORPAY_KEY_SECRET)).thenReturn(secret);
    when(settings.get(Settings.RAZORPAY_WEBHOOK_SECRET)).thenReturn(webhook);
  }

  @Test
  void missingKeysAreReportedWithoutACall() {
    keys("rzp_test_1", "", "w");
    var r = gateway(local()).checkKeys();
    assertThat(r.ok()).isFalse();
    assertThat(r.message()).contains("not both saved");
    assertThat(authHeader.get()).isEqualTo("none");
  }

  @Test
  void acceptedTestKeysWithWebhook() {
    keys("rzp_test_1", "sec", "whsec");
    var r = gateway(local()).checkKeys();
    assertThat(r.ok()).isTrue();
    assertThat(r.message()).contains("test mode").doesNotContain("webhook secret is still missing");
    assertThat(authHeader.get()).startsWith("Basic ");
  }

  @Test
  void acceptedLiveKeysWithoutWebhookWarn() {
    keys("rzp_live_1", "sec", "");
    var r = gateway(local()).checkKeys();
    assertThat(r.ok()).isTrue();
    assertThat(r.message()).contains("LIVE mode").contains("webhook secret is still missing");
  }

  @Test
  void wrongKeysAre401() {
    keys("rzp_test_1", "sec", "w");
    status.set(401);
    var r = gateway(local()).checkKeys();
    assertThat(r.ok()).isFalse();
    assertThat(r.message()).contains("refused the keys");
  }

  @Test
  void otherClientErrorsAreQuoted() {
    keys("rzp_test_1", "sec", "w");
    status.set(400);
    assertThat(gateway(local()).checkKeys().message()).isEqualTo("Razorpay answered 400.");
  }

  @Test
  void unreachableGatewayIsReportedByType() {
    keys("rzp_test_1", "sec", "w");
    String base = local();
    server.stop(0);
    var r = gateway(base).checkKeys();
    assertThat(r.ok()).isFalse();
    assertThat(r.message()).startsWith("Could not reach Razorpay: ");
  }

  @Test
  void keysEmptiedBetweenTheCheckAndTheSignedCallAreNotSent() {
    // configured() sees the keys, then the saved id is cleared before the call is signed: no Authorization header.
    when(settings.get(Settings.RAZORPAY_KEY_ID)).thenReturn("rzp_test_1", "", "");
    when(settings.get(Settings.RAZORPAY_KEY_SECRET)).thenReturn("sec");
    when(settings.get(Settings.RAZORPAY_WEBHOOK_SECRET)).thenReturn("w");
    assertThat(gateway(local()).checkKeys().ok()).isTrue();
    assertThat(authHeader.get()).isEqualTo("null");
  }

  @Test
  void secretEmptiedBetweenTheCheckAndTheSignedCallIsNotSent() {
    when(settings.get(Settings.RAZORPAY_KEY_ID)).thenReturn("rzp_test_1");
    when(settings.get(Settings.RAZORPAY_KEY_SECRET)).thenReturn("sec", "");
    when(settings.get(Settings.RAZORPAY_WEBHOOK_SECRET)).thenReturn("w");
    assertThat(gateway(local()).checkKeys().ok()).isTrue();
    assertThat(authHeader.get()).isEqualTo("null");
  }
}
