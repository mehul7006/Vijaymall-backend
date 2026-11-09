package com.vijay.mall.payment.paytm.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.SettlementProvider;
import com.vijay.mall.payment.domain.SettlementProvider.Detail;
import com.vijay.mall.payment.domain.SettlementProvider.Item;
import com.vijay.mall.payment.domain.SettlementProvider.ItemKind;
import com.vijay.mall.payment.domain.StatementParser;
import com.vijay.mall.payment.paytm.PaytmTestSettings;
import com.vijay.mall.payment.paytm.settlement.PaytmSettlementApiClient.Api;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Paytm Settlement Report mapped to settlement items (CSV format) and the Paytm settlement provider that plugs the API into the registry. */
class PaytmStatementTest {
  private static final String HEADER = "Transaction ID,Order ID,Transaction Date,Updated date,Transaction Type,Status,Amount,Commission,GST,Settled amount,Settled Date,UTR no.,Split Flag,Payment Mode";
  private final PaytmStatementParser parser = new PaytmStatementParser(new PaytmSettlementReportParser());

  private StatementParser.Result read(String body) { return parser.parse((HEADER + "\n" + body).getBytes(StandardCharsets.UTF_8)); }

  @Test void recognisesThePaytmHeaderAndNothingElse() {
    assertThat(parser.format()).isEqualTo("PAYTM");
    assertThat(parser.provider()).isEqualTo("paytm");
    assertThat(parser.recognises(HEADER)).isTrue();
    assertThat(parser.recognises("﻿\"Transaction ID\";\"Commission\";\"Settled Amount\"")).isTrue();
    assertThat(parser.recognises("date,reference,amount,fee,tax")).isFalse();
    assertThat(parser.recognises(null)).isFalse();
  }

  @Test void aSaleBecomesAPaymentWithFeeGstNetUtrAndTheOrderIdAsReference() {
    var r = read("T1,0a1b2c3d4e5f60718293a4b5c6d7e8f9,2026-11-08 10:22:33,,ACQUIRING,SUCCESS,\"1,250.00\",10.00,1.80,1238.20,2026-11-10,UTR55,N,Credit Card\n");
    assertThat(r.problems()).isEmpty();
    Item i = r.items().get(0);
    assertThat(i.kind()).isEqualTo(ItemKind.PAYMENT);
    assertThat(i.entityId()).isEqualTo("T1");
    assertThat(i.grossPaise()).isEqualTo(125000);
    assertThat(i.feePaise()).isEqualTo(1000);
    assertThat(i.taxPaise()).isEqualTo(180);
    assertThat(i.netPaise()).isEqualTo(123820);
    assertThat(i.method()).isEqualTo("card");
    assertThat(i.utr()).isEqualTo("UTR55");
    assertThat(i.reference()).isEqualTo("0a1b2c3d4e5f60718293a4b5c6d7e8f9");
    assertThat(i.altReference()).isNull();
    assertThat(i.settledAt()).isEqualTo(Instant.parse("2026-11-09T18:30:00Z"));
    assertThat(i.detail()).containsEntry("transactionDate", "2026-11-08T10:22:33").containsEntry("transactionType", "ACQUIRING").containsEntry("splitFlag", "N")
        .doesNotContainKeys("posId", "vpa");
  }

  @Test void netIsWorkedOutWhenTheSettledAmountIsMissingAndARefundTakesMoneyAway() {
    var r = read("T2,O2,2026-11-08 10:00:00,,ACQUIRING,SUCCESS,100.00,2.00,0.36,,2026-11-10,U1,,UPI\n"
        + "T3,O3,2026-11-08 11:00:00,,REFUND,SUCCESS,-40.00,0,0,-40.00,2026-11-10,U1,,UPI\n"
        + "T4,O4,2026-11-08 11:00:00,,CHARGEBACK,SUCCESS,25.00,0,0,,2026-11-10,U1,,Wallet\n");
    assertThat(r.items()).extracting(Item::kind).containsExactly(ItemKind.PAYMENT, ItemKind.ADJUSTMENT, ItemKind.ADJUSTMENT);
    assertThat(r.items().get(0).netPaise()).isEqualTo(9764);
    assertThat(r.items().get(1).netPaise()).isEqualTo(-4000);
    assertThat(r.items().get(1).grossPaise()).isEqualTo(4000);
    assertThat(r.items().get(2).netPaise()).isEqualTo(-2500);
    assertThat(r.items().get(2).method()).isEqualTo("balance");
  }

  @Test void linesThatMovedNoMoneyAreLeftOutAndBadRowsAreReportedWithTheirLine() {
    var r = read("T5,O5,2026-11-08 10:00:00,,ACQUIRING,FAILURE,10.00,0,0,0,,,,UPI\n"
        + "T6,O6,2026-11-08 10:00:00,,ACQUIRING,PENDING,10.00,0,0,0,,,,UPI\n"
        + "T7,O7,2026-11-08 10:00:00,,ACQUIRING,CANCELLED,10.00,0,0,0,,,,UPI\n"
        + "T8,O8,2026-11-08 10:00:00,,ACQUIRING,SUCCESS,abc,0,0,0,,,,UPI\n");
    assertThat(r.items()).isEmpty();
    assertThat(r.problems()).singleElement().asString().startsWith("row 5:");
  }

  @Test void aFileWithoutTheRequiredColumnsIsCsvInvalid() {
    assertThatThrownBy(() -> parser.parse("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8))).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("CSV_INVALID"));
  }

  @Test void aLineWithoutAnOrderIdFallsBackToTheRrnAndKeepsApiFields() {
    PaytmSettlementRecord r = new PaytmSettlementRecord("paytm", "API_DETAIL", "T9", null, null, "SUCCESS", "UPI", "UPI_QR_CODE", "998877665544", null, null,
        "cu***@okbank", "POS1", "SER9", 5000L, null, 50L, 9L, "P1", "UTR9", LocalDateTime.of(2026, 11, 8, 9, 0), null, null, null, null, Map.of("extra", "x"));
    Item i = PaytmSettlementMapper.toItem(r);
    assertThat(i.kind()).isEqualTo(ItemKind.PAYMENT);
    assertThat(i.reference()).isEqualTo("998877665544");
    assertThat(i.altReference()).isNull();
    assertThat(i.netPaise()).isEqualTo(4941);
    assertThat(i.settledAt()).isEqualTo(Instant.parse("2026-11-08T03:30:00Z"));
    assertThat(i.detail()).containsEntry("posId", "POS1").containsEntry("extSerialNo", "SER9").containsEntry("vpa", "cu***@okbank").containsEntry("extra", "x")
        .containsEntry("requestType", "UPI_QR_CODE");
    PaytmSettlementRecord bare = new PaytmSettlementRecord("paytm", "CSV", "T10", "O10", "REFUND", "SUCCESS", "OTHER", null, "RR", null, null, null, null, null, null,
        1500L, null, null, null, null, null, null, null, null, null, null);
    Item b = PaytmSettlementMapper.toItem(bare);
    assertThat(b.method()).isNull();
    assertThat(b.settledAt()).isNull();
    assertThat(b.altReference()).isEqualTo("RR");
    assertThat(b.netPaise()).isEqualTo(-1500);
    assertThat(b.grossPaise()).isEqualTo(1500);
  }

  // ---------- the provider ----------
  private final PaytmSettlementApiClient client = mock(PaytmSettlementApiClient.class);

  private PaytmSettlementRecord rec(String txn, String payout, String utr, LocalDate settled, long amount, long fee) {
    return new PaytmSettlementRecord("paytm", "API_DETAIL", txn, "ORD" + txn, "ACQUIRING", "SUCCESS", "UPI", null, "RRN" + txn, null, null, null, null, null, amount,
        amount - fee, fee, 0L, payout, utr, LocalDateTime.of(2026, 11, 8, 9, 0), settled, null, null, null, Map.of());
  }

  @Test void configuredNeedsTheSwitchTheMerchantIdAndTheSecretOfTheChosenSignIn() {
    var values = new java.util.HashMap<String, String>();
    var p = new PaytmSettlementProvider(client, PaytmTestSettings.of(values));
    assertThat(p.provider()).isEqualTo("paytm");
    assertThat(p.pulls()).isTrue();
    assertThat(p.configured()).isFalse();
    values.put("paytm.settlement.enabled", "true");
    values.put("paytm.mid", "M1");
    values.put("paytm.settlement.authStyle", "BEARER");
    assertThat(p.configured()).isFalse();
    values.put("paytm.settlement.token", "tok");
    assertThat(p.configured()).isTrue();
    values.put("paytm.settlement.authStyle", "CHECKSUM");
    assertThat(p.configured()).isFalse();
    values.put("paytm.merchantKey", "0123456789abcdef");
    assertThat(p.configured()).isTrue();
    assertThat(p.fetchPaymentDetail("x")).isEmpty();
  }

  @Test void listsOneSettlementPerPayoutAndServesItsDetailFromTheListing() {
    var p = new PaytmSettlementProvider(client, PaytmTestSettings.of(Map.of()));
    LocalDate d = LocalDate.of(2026, 11, 10);
    when(client.fetch(any(Api.class), any(), any())).thenReturn(List.of(rec("A", "PO1", "U1", d, 10000, 100), rec("B", "PO1", "U1", d, 5000, 50), rec("C", null, "U2", d, 700, 7),
        rec("D", null, null, d, 800, 8), rec("E", null, null, null, 900, 9),
        new PaytmSettlementRecord("paytm", "API_DETAIL", "F", "OF", "ACQUIRING", "FAILURE", "UPI", null, null, null, null, null, null, null, 5L, 0L, 0L, 0L, "PO1", null, null, null,
            null, null, null, Map.of())));
    SettlementProvider.Listing l = p.listSettlements(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-10T00:00:00Z"), null);
    assertThat(l.nextCursor()).isNull();
    assertThat(l.items()).extracting(SettlementProvider.Summary::id).containsExactly("paytm-PO1", "paytm-utr-U2", "paytm-date-2026-11-10", "paytm-unsettled");
    SettlementProvider.Summary po1 = l.items().get(0);
    assertThat(po1.grossPaise()).isEqualTo(15000);
    assertThat(po1.feePaise()).isEqualTo(150);
    assertThat(po1.netPaise()).isEqualTo(14850);
    assertThat(po1.utr()).isEqualTo("U1");
    assertThat(po1.settledAt()).isEqualTo(Instant.parse("2026-11-09T18:30:00Z"));
    assertThat(l.items().get(3).settledAt()).isNull();
    Detail detail = p.settlementDetail("paytm-PO1").orElseThrow();
    assertThat(detail.items()).hasSize(2);
  }

  @Test void aDetailNotInTheLastListingIsReadAgainFromTheLastDays() {
    var p = new PaytmSettlementProvider(client, PaytmTestSettings.of(Map.of()));
    when(client.sync(Api.DETAIL)).thenReturn(List.of(rec("A", "PO9", "U9", LocalDate.of(2026, 11, 10), 100, 1)));
    assertThat(p.settlementDetail("paytm-PO9")).isPresent();
    assertThat(p.settlementDetail("paytm-none")).isEmpty();
  }
}
