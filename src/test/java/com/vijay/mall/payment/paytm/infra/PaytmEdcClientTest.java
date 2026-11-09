package com.vijay.mall.payment.paytm.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.payment.paytm.PaytmFakeServer;
import com.vijay.mall.payment.paytm.PaytmFakeServer.Script;
import com.vijay.mall.payment.paytm.PaytmTestSettings;
import com.vijay.mall.payment.paytm.domain.PaytmReply;
import com.vijay.mall.payment.paytm.domain.PaytmResultCode.Outcome;
import com.vijay.mall.payment.paytm.domain.PaytmTerminalService.Sale;
import com.vijay.mall.payment.paytm.domain.PaytmTerminalService.Txn;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The Paytm HTTP client against the in-JVM fake. */
class PaytmEdcClientTest {
  private PaytmFakeServer fake;
  private Map<String, String> cfg;
  private PaytmEdcClient client;
  private static final String DT = "2026-11-09 10:00:00";

  @BeforeEach
  void start() {
    fake = new PaytmFakeServer();
    cfg = PaytmTestSettings.values(fake.baseUrl());
    client = new PaytmEdcClient(PaytmTestSettings.of(cfg), JsonMapper.builder().build(), Clock.fixed(Instant.parse("2026-11-09T04:30:00Z"), ZoneOffset.UTC), 1);
  }

  @AfterEach
  void stop() { fake.close(); }

  private Sale sale(String id) { return new Sale("T1234567", id, DT, 12345, "Bill #INV/7 <x>"); }

  private Txn txn(String id) { return new Txn("T1234567", id, DT); }

  @Test
  void saleIsSignedAndShapedAsDocumented() {
    PaytmReply r = client.requestSale(sale("AAAAAAAA1"));
    assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(r.code()).isEqualTo("0009");
    assertThat(fake.lastHead.path("requestTimeStamp").asString()).isEqualTo("2026-11-09 10:00:00");
    assertThat(fake.lastHead.path("channelId").asString()).isEqualTo("EDC");
    assertThat(fake.lastBody.path("transactionAmount").asString()).isEqualTo("12345");
    assertThat(fake.lastBody.path("merchantReferenceNo").asString()).isEqualTo("Bill INV/7 x");
    assertThat(fake.lastBody.path("merchantExtendedInfo").path("autoAccept").asString()).isEqualTo("true");
    assertThat(fake.lastBody.path("merchantExtendedInfo").path("paymentMode").asString()).isEqualTo("ALL");
    assertThat(fake.lastBody.has("timeoutConfig")).isFalse();
  }

  @Test
  void timeoutConfigAndModeAreSentWhenConfigured() {
    cfg.put("paytm.timeoutConfig", "5");
    cfg.put("paytm.paymentMode", "CARD");
    cfg.put("paytm.autoAccept", "false");
    client.requestSale(sale("AAAAAAAA2"));
    assertThat(fake.lastBody.path("timeoutConfig").asInt()).isEqualTo(5);
    assertThat(fake.lastBody.path("merchantExtendedInfo").path("paymentMode").asString()).isEqualTo("CARD");
    assertThat(fake.lastBody.path("merchantExtendedInfo").path("autoAccept").asString()).isEqualTo("false");
  }

  @Test
  void rejectionsAreTypedWithPlainMessages() {
    fake.next(Script.TERMINAL_INACTIVE).next(Script.BUSY).next(Script.DUPLICATE).next(Script.BAD_CHECKSUM);
    assertThat(client.requestSale(sale("AAAAAAAA3")).internalCode()).isEqualTo("TERMINAL_INACTIVE");
    assertThat(client.requestSale(sale("AAAAAAAA4")).internalCode()).isEqualTo("TERMINAL_BUSY_AT_PAYTM");
    PaytmReply dup = client.requestSale(sale("AAAAAAAA5"));
    assertThat(dup.outcome()).isEqualTo(Outcome.DUPLICATE);
    PaytmReply bad = client.requestSale(sale("AAAAAAAA6"));
    assertThat(bad.internalCode()).isEqualTo("BAD_CHECKSUM");
    assertThat(bad.message()).contains("merchant key");
  }

  @Test
  void aWrongKeyIsAnsweredWithBadChecksum() {
    cfg.put("paytm.merchantKey", "fedcba9876543210");
    PaytmReply r = client.requestSale(sale("AAAAAAAA7"));
    assertThat(r.internalCode()).isEqualTo("BAD_CHECKSUM");
  }

  @Test
  void unknownCodesKeepTheRawCodeAndCleanTheText() {
    fake.next(Script.UNKNOWN_CODE);
    PaytmReply r = client.requestSale(sale("AAAAAAAA8"));
    assertThat(r.outcome()).isEqualTo(Outcome.ERROR);
    assertThat(r.code()).isEqualTo("0999");
    assertThat(r.internalCode()).isEqualTo("PAYTM_ERROR");
    assertThat(r.message()).contains("0999").doesNotContain("<");
  }

  @Test
  void statusFollowsPendingThenCardSuccessWithFields() {
    client.requestSale(sale("AAAAAAAA9"));
    assertThat(client.status(txn("AAAAAAAA9")).outcome()).isEqualTo(Outcome.PENDING);
    PaytmReply ok = client.status(txn("AAAAAAAA9"));
    assertThat(ok.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(ok.details().payMethod()).isEqualTo("CREDIT_CARD");
    assertThat(ok.details().rrn()).isEqualTo("412345678901");
    assertThat(ok.details().authCode()).isEqualTo("A1B2C3");
    assertThat(ok.details().maskedCard()).isEqualTo("411111XXXXXX1111");
    assertThat(ok.details().amountPaise()).isEqualTo(12345L);
    assertThat(ok.details().raw()).contains("retrievalReferenceNo");
  }

  @Test
  void upiSuccessAndNotFoundAndFailed() {
    fake.next(Script.UPI_SUCCESS).next(Script.CANCELLED_ON_MACHINE);
    client.requestSale(sale("BBBBBBBB1"));
    PaytmReply upi = client.status(txn("BBBBBBBB1"));
    assertThat(upi.details().payMethod()).isEqualTo("UPI");
    assertThat(upi.details().upiVpa()).isEqualTo("customer@okbank");
    assertThat(client.status(txn("NOSUCH0001")).outcome()).isEqualTo(Outcome.NOT_FOUND);
    client.requestSale(sale("BBBBBBBB2"));
    assertThat(client.status(txn("BBBBBBBB2")).outcome()).isEqualTo(Outcome.PENDING);
    assertThat(client.status(txn("BBBBBBBB2")).outcome()).isEqualTo(Outcome.FAILED);
    assertThat(client.cancelPending(txn("BBBBBBBB2")).cancelled()).isTrue();
    assertThat(client.cancelPending(txn("NOSUCH0002")).cancelled()).isFalse();
  }

  @Test
  void voidAcceptedAndRefused() {
    assertThat(client.voidSale(txn("CCCCCCCC1")).outcome()).isEqualTo(Outcome.ACCEPTED);
    fake.voidCode = "0182";
    PaytmReply r = client.voidSale(txn("CCCCCCCC1"));
    assertThat(r.internalCode()).isEqualTo("VOID_ALREADY_REQUESTED");
  }

  @Test
  void aSaleIsNeverRetriedButStatusIs() {
    fake.next(Script.SERVER_ERROR_NEVER_ARRIVED);
    PaytmReply r = client.requestSale(sale("DDDDDDDD1"));
    assertThat(r.outcome()).isEqualTo(Outcome.NETWORK_ERROR);
    assertThat(fake.count("/ecr/payment/request")).isEqualTo(1);
    fake.next(Script.DROP_THEN_CARD);
    assertThat(client.requestSale(sale("DDDDDDDD2")).outcome()).isEqualTo(Outcome.NETWORK_ERROR);
    assertThat(fake.count("/ecr/payment/request")).isEqualTo(2);
    fake.close();
    PaytmReply down = client.status(txn("DDDDDDDD2"));
    assertThat(down.outcome()).isEqualTo(Outcome.NETWORK_ERROR);
    assertThat(down.answered()).isFalse();
  }

  @Test
  void anUnreadableAnswerIsAnErrorOrANetworkProblem() throws Exception {
    var plain = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    plain.createContext("/", ex -> {
      byte[] b = "<html>nope</html>".getBytes();
      ex.sendResponseHeaders(ex.getRequestURI().getPath().equals("/ecr/void") ? 502 : 403, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    plain.start();
    try {
      cfg.put("paytm.baseUrl", "http://127.0.0.1:" + plain.getAddress().getPort() + "/");
      PaytmReply r = client.requestSale(sale("EEEEEEEE1"));
      assertThat(r.outcome()).isEqualTo(Outcome.ERROR);
      assertThat(r.internalCode()).isEqualTo("PAYTM_HTTP_403");
      assertThat(client.voidSale(txn("EEEEEEEE1")).outcome()).isEqualTo(Outcome.NETWORK_ERROR);
    } finally {
      plain.stop(0);
    }
  }

  @Test
  void testConnectionReportsEachSituation() {
    PaytmTerminalResult ok = new PaytmTerminalResult(client.testConnection("T1234567"));
    assertThat(ok.code()).isEqualTo("OK");
    assertThat(ok.ok()).isTrue();
    assertThat(fake.lastBody.path("merchantTransactionId").asString()).startsWith("VMTEST");
    fake.inactiveTerminals.add("T1234567");
    assertThat(client.testConnection("T1234567").code()).isEqualTo("TERMINAL_INACTIVE");
    fake.inactiveTerminals.clear();
    cfg.put("paytm.merchantKey", "fedcba9876543210");
    assertThat(client.testConnection("T1234567").code()).isEqualTo("BAD_CREDENTIALS");
    cfg.put("paytm.merchantKey", PaytmFakeServer.KEY);
    cfg.put("paytm.mid", "");
    assertThat(client.testConnection("T1234567").code()).isEqualTo("NOT_CONFIGURED");
    cfg.put("paytm.mid", "TESTMID123");
    fake.close();
    assertThat(client.testConnection("T1234567").code()).isEqualTo("NETWORK");
  }

  @Test
  void testConnectionHandlesOddAnswers() throws Exception {
    String[] answer = {"401|", "200|{\"body\":{\"resultInfo\":{\"resultCode\":\"0010\"}}}", "200|{\"body\":{\"resultInfo\":{\"resultCode\":\"0888\",\"resultMsg\":\"odd\"}}}"};
    int[] at = {0};
    var plain = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    plain.createContext("/", ex -> {
      String[] a = answer[at[0]].split("\\|", -1);
      byte[] b = a[1].getBytes();
      ex.sendResponseHeaders(Integer.parseInt(a[0]), b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    plain.start();
    try {
      cfg.put("paytm.baseUrl", "http://127.0.0.1:" + plain.getAddress().getPort());
      assertThat(client.testConnection("T1234567").code()).isEqualTo("BAD_CREDENTIALS");
      at[0] = 1;
      assertThat(client.testConnection("T1234567").ok()).isTrue();
      at[0] = 2;
      var r = client.testConnection("T1234567");
      assertThat(r.ok()).isFalse();
      assertThat(r.message()).contains("0888");
    } finally {
      plain.stop(0);
    }
  }

  private record PaytmTerminalResult(boolean ok, String code) {
    PaytmTerminalResult(com.vijay.mall.payment.paytm.domain.PaytmTerminalService.ConnectionResult r) { this(r.ok(), r.code()); }
  }
}
