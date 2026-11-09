package com.vijay.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.payment.app.PaymentGatewayRegistry;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.domain.PaymentGateway;
import com.vijay.mall.payment.infra.PaymentRepository;
import com.vijay.mall.payment.infra.RazorpayGateway;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A refund the provider already accepted (its answer was lost) must be found again, not sent a second time. */
class RefundNotSentTwiceTest {
  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private final UUID refundId = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();
  private final PaymentRepository repo = mock(PaymentRepository.class);
  private final PaymentGateway gateway = mock(PaymentGateway.class);
  private PaymentService service;

  private void setUp(int attempts) {
    when(gateway.provider()).thenReturn("fake");
    service = new PaymentService(repo, new PaymentGatewayRegistry(List.of(gateway),
        new AppProps(null, null, null, null, new AppProps.Payment("fake", 15, null, null, null, null), null)), mock(ApplicationEventPublisher.class),
        mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    when(repo.pendingRefunds(50)).thenReturn(List.of(new PaymentRepository.PendingRefund(refundId, paymentId, "fake", "pay_1", 5000, "cancelled", attempts)));
    when(repo.claimRefund(refundId, NOW)).thenReturn(true);
  }

  @Test
  void aFirstTryIsSentWithoutLookingForAnEarlierOne() {
    setUp(0);
    when(gateway.refund("pay_1", 5000, "cancelled", refundId.toString())).thenReturn("rfnd_1");
    service.sendQueuedRefunds();
    verify(gateway, never()).findRefund(anyString(), anyString());
    verify(repo).refundIssued(refundId, "rfnd_1", NOW);
  }

  @Test
  void aRetryFirstLooksForTheRefundTheProviderMayAlreadyHave() {
    setUp(1);
    when(gateway.findRefund("pay_1", refundId.toString())).thenReturn(Optional.of("rfnd_old"));
    service.sendQueuedRefunds();
    verify(gateway, never()).refund(anyString(), anyLong(), anyString(), anyString());
    verify(repo).refundIssued(refundId, "rfnd_old", NOW);
  }

  @Test
  void aRetryWithNothingAtTheProviderSendsIt() {
    setUp(2);
    when(gateway.findRefund("pay_1", refundId.toString())).thenReturn(Optional.empty());
    when(gateway.refund("pay_1", 5000, "cancelled", refundId.toString())).thenReturn("rfnd_new");
    service.sendQueuedRefunds();
    verify(repo).refundIssued(refundId, "rfnd_new", NOW);
  }

  @Test
  void aProviderWithoutLookupSupportJustSendsTheRefund() {
    PaymentGateway plain = new PaymentGateway() {
      @Override public String provider() { return "plain"; }
      @Override public Created create(CreateRequest r) { return null; }
      @Override public com.vijay.mall.payment.domain.PaymentSession session(String id, long amount) { return null; }
      @Override public com.vijay.mall.payment.domain.PaymentEvent parseWebhook(String b, java.util.Map<String, String> h) { return null; }
      @Override public String refund(String ref, long amount, String reason) { return "plain_" + ref; }
      @Override public Optional<com.vijay.mall.payment.domain.PaymentEvent> fetchStatus(String id) { return Optional.empty(); }
    };
    assertThat(plain.refund("pay_1", 5, "r", "our-id")).isEqualTo("plain_pay_1");
    assertThat(plain.findRefund("pay_1", "our-id")).isEmpty();
  }

  // ---------- the real Razorpay calls, against a local fake ----------
  @Test
  void razorpayTagsTheRefundWithOurIdAndCanFindItAgain() throws Exception {
    List<String> seen = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + body);
      String out = ex.getRequestURI().getPath().endsWith("/refunds")
          ? "{\"items\":[{\"id\":\"rfnd_other\",\"receipt\":\"someone-else\"},{\"id\":\"rfnd_mine\",\"receipt\":\"" + refundId + "\"},"
              + "{\"id\":\"rfnd_notes\",\"notes\":{\"refundId\":\"n-" + refundId + "\"}}]}"
          : "{\"id\":\"rfnd_77\"}";
      byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    });
    server.start();
    try {
      var g = new RazorpayGateway(new AppProps(null, null, null, null, new AppProps.Payment("razorpay", 15, "k", "s", "w",
          "http://127.0.0.1:" + server.getAddress().getPort()), null), JsonMapper.builder().build());
      assertThat(g.refund("pay_1", 5000, "cancelled", refundId.toString())).isEqualTo("rfnd_77");
      assertThat(seen.get(0)).startsWith("POST /v1/payments/pay_1/refund").contains("\"receipt\":\"" + refundId + "\"").contains("\"refundId\":\"" + refundId + "\"");
      assertThat(g.findRefund("pay_1", refundId.toString())).contains("rfnd_mine");
      assertThat(g.findRefund("pay_1", "n-" + refundId)).contains("rfnd_notes");
      assertThat(g.findRefund("pay_1", "unknown")).isEmpty();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void razorpayLookupWithAnEmptyAnswerFindsNothingAndNeedsKeys() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      ex.getRequestBody().readAllBytes();
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var g = new RazorpayGateway(new AppProps(null, null, null, null, new AppProps.Payment("razorpay", 15, "k", "s", "w", base), null), JsonMapper.builder().build());
      assertThat(g.findRefund("pay_1", "x")).isEmpty();
      assertThat(g.refund("pay_1", 1, "r", "x")).isNull();
      var noKeys = new RazorpayGateway(new AppProps(null, null, null, null, new AppProps.Payment("razorpay", 15, "", "", "w", base), null), JsonMapper.builder().build());
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> noKeys.findRefund("pay_1", "x")).isInstanceOf(com.vijay.mall.common.error.ApiException.class);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> noKeys.refund("pay_1", 1, "r", "x")).isInstanceOf(com.vijay.mall.common.error.ApiException.class);
    } finally {
      server.stop(0);
    }
  }

  /** Starts a fake Razorpay whose refund list for pay_1 is served by {@code pages} (query "skip" picks the page). */
  private HttpServer pagedServer(List<String> queries, java.util.function.IntFunction<String> pageBySkip) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      queries.add(ex.getRequestURI().getRawQuery());
      String q = ex.getRequestURI().getRawQuery();
      int skip = Integer.parseInt(q.substring(q.indexOf("skip=") + 5));
      byte[] bytes = pageBySkip.apply(skip).getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    });
    server.start();
    return server;
  }

  private static String fullPage(String firstId, String receipt) {
    StringBuilder sb = new StringBuilder("{\"items\":[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"id\":\"").append(i == 0 ? firstId : "rfnd_f" + i).append("\",\"receipt\":\"").append(i == 0 ? receipt : "other" + i).append("\"}");
    }
    return sb.append("]}").toString();
  }

  private RazorpayGateway gatewayAt(HttpServer server) {
    return new RazorpayGateway(new AppProps(null, null, null, null, new AppProps.Payment("razorpay", 15, "k", "s", "w",
        "http://127.0.0.1:" + server.getAddress().getPort()), null), JsonMapper.builder().build());
  }

  @Test
  void razorpayLookupFollowsThePagesUntilItFindsTheRefund() throws Exception {
    List<String> queries = new CopyOnWriteArrayList<>();
    HttpServer server = pagedServer(queries, skip -> skip < 200 ? fullPage("rfnd_x" + skip, "o" + skip)
        : "{\"items\":[{\"id\":\"rfnd_late\",\"receipt\":\"" + refundId + "\"}]}");
    try {
      assertThat(gatewayAt(server).findRefund("pay_1", refundId.toString())).contains("rfnd_late");
      assertThat(queries).containsExactly("count=100&skip=0", "count=100&skip=100", "count=100&skip=200");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void razorpayLookupStopsAfterAShortPageAndAfterTheCap() throws Exception {
    List<String> queries = new CopyOnWriteArrayList<>();
    HttpServer server = pagedServer(queries, skip -> skip == 0 ? fullPage("rfnd_a", "a") : "{\"items\":[]}");
    try {
      assertThat(gatewayAt(server).findRefund("pay_1", refundId.toString())).isEmpty();
      assertThat(queries).as("the empty second page ends the search").hasSize(2);
    } finally {
      server.stop(0);
    }
    List<String> endless = new CopyOnWriteArrayList<>();
    HttpServer busy = pagedServer(endless, skip -> fullPage("rfnd_b" + skip, "b" + skip));
    try {
      assertThat(gatewayAt(busy).findRefund("pay_1", refundId.toString())).isEmpty();
      assertThat(endless).as("never more than ten pages").hasSize(10);
    } finally {
      busy.stop(0);
    }
  }
}
