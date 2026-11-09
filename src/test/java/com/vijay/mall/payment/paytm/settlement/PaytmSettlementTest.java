package com.vijay.mall.payment.paytm.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.paytm.PaytmTestSettings;
import com.vijay.mall.payment.paytm.settlement.PaytmSettlementApiClient.Api;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The settlement CSV parser, the value readers and the settlement API client (against a local stand-in). */
class PaytmSettlementTest {
  private static final String HEADER = "Transaction ID,Order ID,Transaction Date,Updated date,Transaction Type,Status,Amount,Commission,GST,Settled amount,Settled Date,UTR no.,Split Flag,Payment Mode";
  private final PaytmSettlementReportParser parser = new PaytmSettlementReportParser();

  @Test
  void readsTheDocumentedColumns() {
    String csv = HEADER + "\r\n"
        + "T1,O1,2026-11-01 10:22:33,2026-11-01 10:30:00,ACQUIRING,SUCCESS,\"1,250.50\",10.25,1.85,1238.40,2026-11-03,UTR123,N,UPI\r\n"
        + "T2,O2,02-11-2026 09:00:00,,REFUND,success,-100.00,0,0,-100,03/11/2026,UTR123,,Credit Card\r\n"
        + "\r\n"
        + "T3,O3,2026-11-01T10:00:00+05:30,,Chargeback,PENDING,5,0.50,0.09,4.41,,,,wallet\r\n";
    var r = parser.parse(csv);
    assertThat(r.errors()).isEmpty();
    assertThat(r.records()).hasSize(3);
    PaytmSettlementRecord a = r.records().get(0);
    assertThat(a.provider()).isEqualTo("paytm");
    assertThat(a.source()).isEqualTo("CSV");
    assertThat(a.transactionId()).isEqualTo("T1");
    assertThat(a.orderId()).isEqualTo("O1");
    assertThat(a.amountPaise()).isEqualTo(125050L);
    assertThat(a.commissionPaise()).isEqualTo(1025L);
    assertThat(a.gstPaise()).isEqualTo(185L);
    assertThat(a.settledAmountPaise()).isEqualTo(123840L);
    assertThat(a.transactionType()).isEqualTo("ACQUIRING");
    assertThat(a.paymentMode()).isEqualTo("UPI");
    assertThat(a.utr()).isEqualTo("UTR123");
    assertThat(a.transactionDate()).isEqualTo(LocalDateTime.of(2026, 11, 1, 10, 22, 33));
    assertThat(a.settledDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    assertThat(a.splitFlag()).isEqualTo("N");
    assertThat(a.detail()).containsEntry("updatedDate", "2026-11-01T10:30");
    PaytmSettlementRecord b = r.records().get(1);
    assertThat(b.amountPaise()).isEqualTo(-10000L);
    assertThat(b.status()).isEqualTo("SUCCESS");
    assertThat(b.transactionType()).isEqualTo("REFUND");
    assertThat(b.paymentMode()).isEqualTo("CARD");
    assertThat(b.transactionDate()).isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 0));
    assertThat(b.settledDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    PaytmSettlementRecord c = r.records().get(2);
    assertThat(c.transactionType()).isEqualTo("CHARGEBACK");
    assertThat(c.paymentMode()).isEqualTo("BALANCE");
    assertThat(c.settledDate()).isNull();
    assertThat(c.transactionDate()).isEqualTo(LocalDateTime.of(2026, 11, 1, 10, 0));
  }

  @Test
  void toleratesBomHeaderCaseAndSpacing() {
    String csv = "﻿" + "  transaction id , STATUS,amount ,utr,settlement date,payment mode,Updated Date\n"
        + "T9,ok,\"Rs. 1,000.00\",U9,2026-11-05,netbanking,\n";
    var r = parser.parse(csv);
    assertThat(r.records()).hasSize(1);
    PaytmSettlementRecord x = r.records().get(0);
    assertThat(x.amountPaise()).isEqualTo(100000L);
    assertThat(x.utr()).isEqualTo("U9");
    assertThat(x.paymentMode()).isEqualTo("NETBANKING");
    assertThat(x.settledDate()).isEqualTo(LocalDate.of(2026, 11, 5));
    assertThat(x.orderId()).isNull();
  }

  @Test
  void badRowsAreReportedAndTheRestIsKept() {
    String csv = HEADER + "\n"
        + ",O1,2026-11-01,,ACQUIRING,SUCCESS,10,0,0,10,,,,UPI\n"
        + "T2,O2,not a date,,ACQUIRING,SUCCESS,10,0,0,10,,,,UPI\n"
        + "T3,O3,2026-11-01,,ACQUIRING,SUCCESS,ten,0,0,10,,,,UPI\n"
        + "T4,O4,2026-11-01,,ACQUIRING,SUCCESS,10.123,0,0,10,,,,UPI\n"
        + "T5,O5,2026-11-01,,ACQUIRING,SUCCESS,10,0,0,10,,,,\n"
        + "T6,O6,2026-11-01,bad,ACQUIRING,SUCCESS,10,0,0,10,,,,PPBL\n"
        + "T7,O7,2026-11-01,,ACQUIRING,SUCCESS,\"(5.00)\",0,0,10,,,,xyz\n";
    var r = parser.parse(csv);
    assertThat(r.records()).extracting(PaytmSettlementRecord::transactionId).containsExactly("T5", "T7");
    assertThat(r.errors()).extracting(PaytmSettlementReportParser.RowError::line).containsExactly(2, 3, 4, 5, 7);
    assertThat(r.records().get(0).paymentMode()).isNull();
    assertThat(r.records().get(1).amountPaise()).isEqualTo(-500L);
    assertThat(r.records().get(1).paymentMode()).isEqualTo("OTHER");
    assertThat(r.records().get(1).detail()).containsEntry("paymentModeRaw", "xyz");
  }

  @Test
  void aFileWithoutTheRequiredColumnsIsRefused() {
    assertThatThrownBy(() -> parser.parse("")).hasMessageContaining("empty");
    assertThatThrownBy(() -> parser.parse(null)).hasMessageContaining("empty");
    assertThatThrownBy(() -> parser.parse("a,b\n1,2")).hasMessageContaining("transactionid");
  }

  @Test
  void quotedFieldsWithCommasQuotesAndLineBreaks() {
    var rows = PaytmSettlementReportParser.rows("a,\"b,c\",\"d\"\"e\"\n\"x\ny\",z");
    assertThat(rows).containsExactly(List.of("a", "b,c", "d\"e"), List.of("x\ny", "z"));
    assertThat(PaytmSettlementReportParser.rows("a,b\r\nc")).hasSize(2);
    assertThat(PaytmSettlementReportParser.rows("")).isEmpty();
    var dates = new ArrayList<String>();
    for (String d : List.of("2026-11-01 10:00", "01-11-2026", "01/11/2026 10:00:00", "01 Nov 2026", "01-Nov-2026 10:00:00", "2026-11-01T10:00:00.123Z"))
      dates.add(PaytmValues.dateTime(d).toString());
    assertThat(dates).allMatch(s -> s.startsWith("2026-11-01"));
    assertThatThrownBy(() -> PaytmValues.dateTime("2026-11-01Tzz")).hasMessageContaining("not a date");
  }

  @Test
  void valueHelpers() {
    assertThat(PaytmValues.paise(null)).isNull();
    assertThat(PaytmValues.paise("  ")).isNull();
    assertThat(PaytmValues.paise("₹ 12.5")).isEqualTo(1250L);
    assertThat(PaytmValues.mode("Debit Card")).isEqualTo("CARD");
    assertThat(PaytmValues.mode(" ")).isNull();
    assertThat(PaytmValues.type("Sale")).isEqualTo("ACQUIRING");
    assertThat(PaytmValues.type("other kind")).isEqualTo("OTHER KIND");
    assertThat(PaytmValues.type(null)).isNull();
    assertThat(PaytmValues.maskVpa("customer@okbank")).isEqualTo("cu***@okbank");
    assertThat(PaytmValues.maskVpa("c@ok")).isEqualTo("c***@ok");
    assertThat(PaytmValues.maskVpa("customer")).isEqualTo("cu***");
    assertThat(PaytmValues.maskVpa("c")).isEqualTo("***");
    assertThat(PaytmValues.maskVpa(" ")).isNull();
    assertThat(PaytmValues.date(null)).isNull();
  }

  // ---------- the API client ----------
  private record Fake(HttpServer server, List<String> seen) {}

  private Fake serve(int status, String body) throws Exception {
    List<String> seen = new CopyOnWriteArrayList<>();
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/", ex -> {
      seen.add(ex.getRequestURI().getPath() + " auth=" + ex.getRequestHeaders().getFirst("Authorization") + " " + new String(ex.getRequestBody().readAllBytes()));
      byte[] b = body.getBytes();
      ex.sendResponseHeaders(status, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    s.start();
    return new Fake(s, seen);
  }

  private PaytmSettlementApiClient client(Map<String, String> cfg) {
    return new PaytmSettlementApiClient(PaytmTestSettings.of(cfg), JsonMapper.builder().build(), Clock.fixed(Instant.parse("2026-11-09T04:30:00Z"), ZoneOffset.UTC));
  }

  private static final String DETAIL = """
      {"body":{"settlementTransactionList":[
       {"transactionId":"T1","orderId":"O1","transactionType":"ACQUIRING","status":"success","paymentMode":"UPI","requestType":"UPI_QR_CODE",
        "rrnCode":"RRN1","payerVpa":"someone@ybl","amount":"100.00","settledAmount":98.2,"commission":1.5,"gst":0.27,"payoutId":"P1","utrNo":"UTR1",
        "transactionDate":"2026-11-08 10:00:00","settledDate":"2026-11-09","posId":"POS1","extSerialNo":"SER1","maskedCardNo":"4111XXXX1111",
        "cardNetwork":"VISA","extra":"keep me","nested":{"a":1},"nothing":null}]}}""";

  @Test
  void apiIsOffMissingConfigOrBrokenGivesClearErrors() throws Exception {
    Map<String, String> cfg = PaytmTestSettings.values("http://127.0.0.1:1");
    assertThatThrownBy(() -> client(cfg).sync(Api.DETAIL)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYTM_SETTLEMENT_DISABLED");
    cfg.put("paytm.settlement.enabled", "true");
    cfg.put("paytm.mid", "");
    assertThatThrownBy(() -> client(cfg).sync(Api.DETAIL)).extracting("code").isEqualTo("PAYTM_NOT_CONFIGURED");
    cfg.put("paytm.mid", "M1");
    cfg.put("paytm.settlement.baseUrl", "http://127.0.0.1:1/");
    assertThatThrownBy(() -> client(cfg).sync(Api.DETAIL)).extracting("code").isEqualTo("PAYTM_SETTLEMENT_ERROR");
    Fake bad = serve(500, "{}");
    Fake junk = serve(200, "{\"body\":{\"nothing\":1}}");
    try {
      cfg.put("paytm.settlement.baseUrl", "http://127.0.0.1:" + bad.server().getAddress().getPort());
      assertThatThrownBy(() -> client(cfg).sync(Api.DETAIL)).extracting("code").isEqualTo("PAYTM_SETTLEMENT_ERROR");
      cfg.put("paytm.settlement.baseUrl", "http://127.0.0.1:" + junk.server().getAddress().getPort());
      assertThatThrownBy(() -> client(cfg).sync(Api.DETAIL)).extracting("code").isEqualTo("PAYTM_SETTLEMENT_ERROR");
    } finally {
      bad.server().stop(0);
      junk.server().stop(0);
    }
  }

  @Test
  void bearerCallNormalizesEverythingAndKeepsTheRest() throws Exception {
    Fake f = serve(200, DETAIL);
    try {
      Map<String, String> cfg = PaytmTestSettings.values("http://unused");
      cfg.put("paytm.settlement.enabled", "true");
      cfg.put("paytm.settlement.baseUrl", "http://127.0.0.1:" + f.server().getAddress().getPort());
      cfg.put("paytm.settlement.token", "tok123");
      cfg.put("paytm.settlement.syncDays", "3");
      List<PaytmSettlementRecord> out = client(cfg).sync(Api.DETAIL);
      assertThat(f.seen().get(0)).startsWith("/merchant-settlement/SettlementDetail auth=Bearer tok123")
          .contains("\"startDate\":\"2026-11-06\"").contains("\"endDate\":\"2026-11-09\"").contains("\"mid\":\"TESTMID123\"");
      PaytmSettlementRecord r = out.get(0);
      assertThat(r.source()).isEqualTo("API_DETAIL");
      assertThat(r.paymentMode()).isEqualTo("UPI");
      assertThat(r.requestType()).isEqualTo("UPI_QR_CODE");
      assertThat(r.rrn()).isEqualTo("RRN1");
      assertThat(r.vpa()).isEqualTo("so***@ybl");
      assertThat(r.amountPaise()).isEqualTo(10000L);
      assertThat(r.settledAmountPaise()).isEqualTo(9820L);
      assertThat(r.commissionPaise()).isEqualTo(150L);
      assertThat(r.gstPaise()).isEqualTo(27L);
      assertThat(r.payoutId()).isEqualTo("P1");
      assertThat(r.utr()).isEqualTo("UTR1");
      assertThat(r.posId()).isEqualTo("POS1");
      assertThat(r.extSerialNo()).isEqualTo("SER1");
      assertThat(r.cardNetwork()).isEqualTo("VISA");
      assertThat(r.maskedCard()).isEqualTo("4111XXXX1111");
      assertThat(r.settledDate()).isEqualTo(LocalDate.of(2026, 11, 9));
      assertThat(r.status()).isEqualTo("SUCCESS");
      assertThat(r.detail()).containsEntry("extra", "keep me").containsEntry("nested", "{\"a\":1}").doesNotContainKey("nothing");
    } finally {
      f.server().stop(0);
    }
  }

  @Test
  void checksumStyleSignsTheBodyAndSummaryReadsBankFields() throws Exception {
    Fake f = serve(200, "[{\"payoutId\":\"P9\",\"utrNo\":\"U9\",\"amount\":10,\"bankName\":\"HDFC\",\"accountNumber\":\"12345678\",\"settledDate\":\"2026-11-09\"}]");
    try {
      Map<String, String> cfg = PaytmTestSettings.values("http://unused");
      cfg.put("paytm.settlement.enabled", "true");
      cfg.put("paytm.settlement.authStyle", "CHECKSUM");
      cfg.put("paytm.settlement.baseUrl", "http://127.0.0.1:" + f.server().getAddress().getPort());
      var out = client(cfg).fetch(Api.SUMMARY, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
      assertThat(f.seen().get(0)).startsWith("/merchant-settlement/SettlementSummary auth=null").contains("\"signature\":\"").contains("\"tokenType\":\"CHECKSUM\"");
      assertThat(out.get(0).bankName()).isEqualTo("HDFC");
      assertThat(out.get(0).bankAccount()).isEqualTo("****5678");
      assertThat(out.get(0).source()).isEqualTo("API_SUMMARY");
    } finally {
      f.server().stop(0);
    }
  }
}
