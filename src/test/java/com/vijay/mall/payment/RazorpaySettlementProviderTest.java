package com.vijay.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.domain.SettlementProvider;
import com.vijay.mall.payment.domain.SettlementProvider.ItemKind;
import com.vijay.mall.payment.infra.RazorpayGateway;
import com.vijay.mall.payment.infra.RazorpaySettlementProvider;
import com.vijay.mall.payment.infra.RazorpaySignature;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Razorpay's settlement API against canned responses shaped like Razorpay's reference. Nothing here has run against a live account yet:
 * these tests prove the request shape (paths, query, basic auth) and the mapping into the neutral port, not Razorpay's real behaviour.
 */
class RazorpaySettlementProviderTest {
  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile String settlementList = "{\"items\":[]}";
  private volatile String recon = "{\"items\":[]}";
  private volatile int reconPageSize = -1;

  private static final String HEAD = "{\"id\":\"setl_1\",\"entity\":\"settlement\",\"amount\":9764,\"status\":\"processed\",\"fees\":236,\"tax\":36,"
      + "\"utr\":\"UTR123\",\"created_at\":1762646400}";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String path = ex.getRequestURI().getPath();
      String q = ex.getRequestURI().getQuery();
      requests.add(ex.getRequestMethod() + " " + path + (q == null ? "" : "?" + q) + " auth=" + (ex.getRequestHeaders().getFirst("Authorization") != null));
      int status = 200;
      String out;
      if (path.equals("/v1/settlements")) out = settlementList;
      else if (path.equals("/v1/settlements/recon/combined")) {
        out = reconPageSize > 0 && q.contains("skip=" + reconPageSize) ? "{\"items\":[" + reconRow("payment", "pay_late", "setl_1", 100, 0, 0, 0) + "]}" : recon;
      } else if (path.equals("/v1/settlements/setl_1")) out = HEAD;
      else if (path.equals("/v1/settlements/empty")) out = "{}";
      else if (path.equals("/v1/payments/pay_upi")) out = "{\"id\":\"pay_upi\",\"method\":\"upi\",\"vpa\":\"a@upi\",\"bank\":\"\",\"fee\":236,\"tax\":36}";
      else if (path.equals("/v1/payments/pay_card")) out = "{\"id\":\"pay_card\",\"method\":\"card\",\"card\":{\"network\":\"Visa\",\"last4\":\"1111\"},\"bank\":\"HDFC\"}";
      else if (path.equals("/v1/payments/blank")) out = "{}";
      else {
        status = 404;
        out = "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\"}}";
      }
      byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(status, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() { server.stop(0); }

  private RazorpaySettlementProvider provider(String keyId) {
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    var gateway = new RazorpayGateway(new AppProps(null, null, null, null, new AppProps.Payment("razorpay", 15, keyId, "sec", "whsec", url), null),
        JsonMapper.builder().build());
    return new RazorpaySettlementProvider(gateway);
  }

  private static String reconRow(String type, String entity, String settlement, long credit, long debit, long fee, long tax) {
    return "{\"entity_id\":\"" + entity + "\",\"type\":\"" + type + "\",\"credit\":" + credit + ",\"debit\":" + debit + ",\"fee\":" + fee + ",\"tax\":" + tax
        + ",\"settlement_id\":\"" + settlement + "\",\"payment_id\":\"" + (type.equals("payment") ? entity : "pay_of_refund") + "\",\"method\":\"upi\","
        + "\"settled_at\":1762646500,\"settlement_utr\":\"UTR123\"}";
  }

  @Test void itNamesItselfAndIsConfiguredOnlyWithKeys() {
    assertThat(provider("rzp_key").provider()).isEqualTo("razorpay");
    assertThat(provider("rzp_key").configured()).isTrue();
    assertThat(provider("").configured()).isFalse();
  }

  @Test void listsSettlementsWithTheDateWindowSeparatingFeeFromItsTax() {
    settlementList = "{\"items\":[" + HEAD + "]}";
    var page = provider("rzp_key").listSettlements(Instant.ofEpochSecond(1762600000), Instant.ofEpochSecond(1762700000), null);
    assertThat(page.nextCursor()).isNull();
    assertThat(page.items()).singleElement().satisfies(s -> {
      assertThat(s.id()).isEqualTo("setl_1");
      assertThat(s.utr()).isEqualTo("UTR123");
      assertThat(s.netPaise()).isEqualTo(9764);
      assertThat(s.grossPaise()).isEqualTo(10000);
      assertThat(s.feePaise()).isEqualTo(200);
      assertThat(s.taxPaise()).isEqualTo(36);
      assertThat(s.settledAt()).isEqualTo(Instant.ofEpochSecond(1762646400));
    });
    assertThat(requests).singleElement().asString().contains("GET /v1/settlements?from=1762600000&to=1762700000&count=100&skip=0").endsWith("auth=true");
  }

  @Test void aFullPageGivesTheNextCursor() {
    settlementList = "{\"items\":[" + IntStream.range(0, 100).mapToObj(i -> HEAD).collect(Collectors.joining(",")) + "]}";
    var p = provider("rzp_key");
    var first = p.listSettlements(Instant.EPOCH, Instant.now(), "");
    assertThat(first.nextCursor()).isEqualTo("100");
    p.listSettlements(Instant.EPOCH, Instant.now(), first.nextCursor());
    assertThat(requests.get(1)).contains("skip=100");
  }

  @Test void anEmptyAnswerIsAnEmptyList() {
    settlementList = "";
    assertThat(provider("rzp_key").listSettlements(Instant.EPOCH, Instant.now(), null).items()).isEmpty();
  }

  @Test void detailReadsTheReconciliationReportOfTheSettlementDayAndKeepsOnlyItsRows() {
    recon = "{\"items\":[" + String.join(",", reconRow("payment", "pay_1", "setl_1", 10000, 0, 236, 36), reconRow("refund", "rfnd_1", "setl_1", 0, 2500, 0, 0),
        reconRow("adjustment", "adj_1", "setl_1", 0, 50, 0, 0), reconRow("payment", "pay_other", "setl_2", 700, 0, 10, 1),
        "{\"entity_id\":\"pay_np\",\"type\":\"payment\",\"credit\":5,\"settlement_id\":\"setl_1\"}") + "]}";
    var d = provider("rzp_key").settlementDetail("setl_1").orElseThrow();
    assertThat(d.summary().id()).isEqualTo("setl_1");
    assertThat(d.items()).hasSize(4);
    assertThat(d.items().get(3).paymentRef()).isEqualTo("pay_np");
    assertThat(d.items().get(3).utr()).as("falls back to the settlement's").isEqualTo("UTR123");
    var pay = d.items().get(0);
    assertThat(pay.kind()).isEqualTo(ItemKind.PAYMENT);
    assertThat(pay.paymentRef()).isEqualTo("pay_1");
    assertThat(pay.grossPaise()).isEqualTo(10000);
    assertThat(pay.feePaise()).isEqualTo(200);
    assertThat(pay.taxPaise()).isEqualTo(36);
    assertThat(pay.netPaise()).isEqualTo(9764);
    assertThat(pay.method()).isEqualTo("upi");
    assertThat(pay.utr()).isEqualTo("UTR123");
    var refund = d.items().get(1);
    assertThat(refund.kind()).isEqualTo(ItemKind.REFUND);
    assertThat(refund.refundRef()).isEqualTo("rfnd_1");
    assertThat(refund.paymentRef()).isEqualTo("pay_of_refund");
    assertThat(refund.grossPaise()).isEqualTo(2500);
    assertThat(refund.netPaise()).isEqualTo(-2500);
    var adj = d.items().get(2);
    assertThat(adj.kind()).isEqualTo(ItemKind.ADJUSTMENT);
    assertThat(adj.grossPaise()).isEqualTo(50);
    assertThat(adj.netPaise()).isEqualTo(-50);
    // the settlement day, in India time: 1762646400 = 2025-11-09 00:00 UTC = 05:30 IST
    assertThat(requests).anySatisfy(r -> assertThat(r).contains("/v1/settlements/recon/combined?year=2025&month=11&day=9&count=500&skip=0"));
  }

  @Test void aLongReportIsReadPageByPage() {
    reconPageSize = 500;
    recon = "{\"items\":[" + IntStream.range(0, 500).mapToObj(i -> reconRow("payment", "pay_" + i, "setl_other", 1, 0, 0, 0)).collect(Collectors.joining(",")) + "]}";
    var d = provider("rzp_key").settlementDetail("setl_1").orElseThrow();
    assertThat(d.items()).extracting(SettlementProvider.Item::paymentRef).containsExactly("pay_late");
  }

  @Test void anUnknownSettlementHasNoDetail() {
    assertThat(provider("rzp_key").settlementDetail("nope")).isEmpty();
    assertThat(provider("rzp_key").settlementDetail("empty")).isEmpty();
  }

  @Test void paymentDetailGivesTheMethodAndOnlyTheLastFourDigits() {
    var upi = provider("rzp_key").fetchPaymentDetail("pay_upi").orElseThrow();
    assertThat(upi.instrument()).isEqualTo("upi");
    assertThat(upi.upiVpa()).isEqualTo("a@upi");
    assertThat(upi.bank()).isNull();
    assertThat(upi.feePaise()).isEqualTo(200);
    assertThat(upi.taxPaise()).isEqualTo(36);
    var card = provider("rzp_key").fetchPaymentDetail("pay_card").orElseThrow();
    assertThat(card.cardNetwork()).isEqualTo("Visa");
    assertThat(card.cardLast4()).isEqualTo("1111");
    assertThat(card.bank()).isEqualTo("HDFC");
    assertThat(card.feePaise()).isNull();
    assertThat(provider("rzp_key").fetchPaymentDetail("missing")).isEmpty();
    assertThat(provider("rzp_key").fetchPaymentDetail("blank")).isEmpty();
  }

  @Test void aSettlementProcessedWebhookBecomesAnEventThatNamesTheSettlement() {
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    var gateway = new RazorpayGateway(new AppProps(null, null, null, null, new AppProps.Payment("razorpay", 15, "k", "sec", "whsec", url), null),
        JsonMapper.builder().build());
    String body = "{\"event\":\"settlement.processed\",\"payload\":{\"settlement\":{\"entity\":{\"id\":\"setl_9\"}}}}";
    var headers = Map.of("x-razorpay-signature", RazorpaySignature.sign(body, "whsec"), "x-razorpay-event-id", "evt_1");
    assertThat(gateway.parseWebhook(body, headers)).isEqualTo(new PaymentEvent.SettlementProcessed("evt_1", "setl_9"));
    String noId = "{\"event\":\"settlement.processed\",\"payload\":{}}";
    assertThat(gateway.parseWebhook(noId, Map.of("x-razorpay-signature", RazorpaySignature.sign(noId, "whsec")))).isInstanceOf(PaymentEvent.Ignored.class);
    String viaFallback = "{\"event\":\"settlement.processed\",\"payload\":{\"settlement\":{\"entity\":{\"id\":\"setl_8\"}}}}";
    assertThat(gateway.parseWebhook(viaFallback, Map.of("x-razorpay-signature", RazorpaySignature.sign(viaFallback, "whsec"))))
        .isEqualTo(new PaymentEvent.SettlementProcessed("settlement.processed:setl_8", "setl_8"));
  }
}
