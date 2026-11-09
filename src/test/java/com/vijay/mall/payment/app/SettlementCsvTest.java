package com.vijay.mall.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementCsvTest {
  private static final SettlementService.CsvMapping DEFAULTS = new SettlementService.CsvMapping(null, null, null, null, null, null, null, null, null);

  private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

  private static SettlementService.CsvMapping with(String dateFormat, String unit, String delimiter) {
    return new SettlementService.CsvMapping(null, null, null, null, null, null, dateFormat, unit, delimiter);
  }

  @Test void readsQuotedCellsLineBreaksCrLfAndABom() {
    String csv = "﻿date,reference,amount,fee,tax,description\r\n2026-11-09,\"A,1\",\"1,234.50\",\"2.00\",0.36,\"line\nbreak \"\"quoted\"\"\"\r\n\r\n2026-11-10T10:00:00,B2,Rs 99,,,\n";
    List<SettlementCsv.Row> rows = SettlementCsv.parse(bytes(csv), DEFAULTS);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).reference()).isEqualTo("A,1");
    assertThat(rows.get(0).amountPaise()).isEqualTo(123450);
    assertThat(rows.get(0).feePaise()).isEqualTo(200);
    assertThat(rows.get(0).taxPaise()).isEqualTo(36);
    assertThat(rows.get(0).description()).isEqualTo("line\nbreak \"quoted\"");
    assertThat(rows.get(0).line()).isEqualTo(2);
    assertThat(rows.get(1).amountPaise()).isEqualTo(9900);
    assertThat(rows.get(1).at()).isEqualTo(Instant.parse("2026-11-10T04:30:00Z"));
    assertThat(rows.get(1).line()).isEqualTo(3);
  }

  @Test void feeAndTaxColumnsAreOptionalAndNegativeFeesAreMadePositive() {
    List<SettlementCsv.Row> rows = SettlementCsv.parse(bytes("DATE,Reference,AMOUNT,Fee\n2026-11-09,x,10,-1.5\n"), DEFAULTS);
    assertThat(rows.get(0).feePaise()).isEqualTo(150);
    assertThat(rows.get(0).taxPaise()).isZero();
    assertThat(rows.get(0).description()).isEmpty();
  }

  @Test void shortRowsReadMissingCellsAsEmpty() {
    assertThatThrownBy(() -> SettlementCsv.parse(bytes("date,reference,amount\n2026-11-09,x\n"), DEFAULTS)).isInstanceOf(ApiException.class);
  }

  @Test void customDateFormatDelimiterAndPaiseUnit() {
    List<SettlementCsv.Row> rows = SettlementCsv.parse(bytes("date;reference;amount\n09-11-2026;r;1500\n"), with("dd-MM-yyyy", "paise", ";"));
    assertThat(rows.get(0).amountPaise()).isEqualTo(1500);
    assertThat(rows.get(0).at()).isEqualTo(Instant.parse("2026-11-08T18:30:00Z"));
    assertThat(SettlementCsv.parse(bytes("date\treference\tamount\n2026-11-09\tr\t5\n"), with("", "RUPEES", "tab"))).hasSize(1);
    assertThat(SettlementCsv.parse(bytes("date,reference,amount\n2026-11-09,r,5\n"), with(" ", null, ""))).hasSize(1);
  }

  @Test void mappedColumnNamesAreUsed() {
    var m = new SettlementService.CsvMapping("Txn Date", "UTR", "Credit", "Charges", "GST", "Narration", null, null, null);
    var rows = SettlementCsv.parse(bytes("Txn Date,UTR,Credit,Charges,GST,Narration\n2026-11-09,U1,10.00,1,0.18,hello\n"), m);
    assertThat(rows.get(0)).extracting(SettlementCsv.Row::reference, SettlementCsv.Row::description).containsExactly("U1", "hello");
  }

  @Test void refusesWhatItCannotRead() {
    assertThatThrownBy(() -> SettlementCsv.parse(new byte[SettlementCsv.MAX_BYTES + 1], DEFAULTS)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("CSV_INVALID"));
    assertThatThrownBy(() -> SettlementCsv.parse(bytes(""), DEFAULTS)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettlementCsv.parse(bytes("date,reference,amount\n"), DEFAULTS)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettlementCsv.parse(bytes("date,reference\n2026-11-09,x\n"), DEFAULTS)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details().toString()).contains("amountColumn"));
    assertThatThrownBy(() -> SettlementCsv.parse(bytes("date,reference,amount\n2026-11-09,x,1\n"), with("bbbb", null, null))).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details().toString()).contains("dateFormat"));
    assertThatThrownBy(() -> SettlementCsv.parse(bytes("date,reference,amount\nx,y,z\n"), with("dd/MM/yyyy", null, null))).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("CSV_INVALID"));
    assertThatThrownBy(() -> SettlementCsv.parse(bytes("date,reference,amount\n2026-11-09,x,1e400\n"), DEFAULTS)).isInstanceOf(ApiException.class);
  }

  @Test void refusesTooManyRows() {
    StringBuilder sb = new StringBuilder("date,reference,amount\n");
    for (int i = 0; i <= SettlementCsv.MAX_ROWS; i++) sb.append("2026-11-09,r").append(i).append(",1\n");
    assertThatThrownBy(() -> SettlementCsv.parse(bytes(sb.toString()), DEFAULTS)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("CSV_INVALID"));
  }

  @Test void aLastRowWithoutALineBreakIsStillRead() {
    assertThat(SettlementCsv.records("a,b\nc,d", ',')).containsExactly(List.of("a", "b"), List.of("c", "d"));
    assertThat(SettlementCsv.records("", ',')).isEmpty();
    assertThat(SettlementCsv.records("\"\"\n", ',')).containsExactly(List.of(""));
  }
}
