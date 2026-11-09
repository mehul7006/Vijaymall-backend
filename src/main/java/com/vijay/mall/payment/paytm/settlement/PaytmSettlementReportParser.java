package com.vijay.mall.payment.paytm.settlement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads the Paytm dashboard "Settlement Report" CSV (columns Transaction ID, Order ID, Transaction Date, Updated date, Transaction
 * Type, Status, Amount, Commission, GST, Settled amount, Settled Date, UTR no., Split Flag, Payment Mode). Tolerant of header case,
 * spaces and punctuation, a byte-order mark, quoted fields and Windows line ends; amounts are rupees with decimals; dates are ISO
 * or dd-MM-yyyy / dd/MM/yyyy. A bad row is reported with its line number and does not stop the file.
 */
@Component
public class PaytmSettlementReportParser {
  /**
   * One row that could not be read.
   * @param line line number in the file, 1 for the header
   * @param message what is wrong
   */
  public record RowError(int line, String message) {}

  /**
   * The result of reading a file.
   * @param records the lines that were read
   * @param errors the lines that were not
   */
  public record Result(List<PaytmSettlementRecord> records, List<RowError> errors) {}

  private static final List<String> REQUIRED = List.of("transactionid", "status", "amount");

  /**
   * Parses the file text.
   *
   * @throws IllegalArgumentException when the header is missing or lacks Transaction ID, Status or Amount
   */
  public Result parse(String csv) {
    List<List<String>> rows = rows(csv == null ? "" : csv);
    if (rows.isEmpty()) throw new IllegalArgumentException("The file is empty");
    Map<String, Integer> col = new LinkedHashMap<>();
    List<String> header = rows.get(0);
    for (int i = 0; i < header.size(); i++) col.putIfAbsent(key(header.get(i)), i);
    for (String r : REQUIRED) if (!col.containsKey(r)) throw new IllegalArgumentException("The file has no column for " + r);
    List<PaytmSettlementRecord> out = new ArrayList<>();
    List<RowError> errors = new ArrayList<>();
    for (int n = 1; n < rows.size(); n++) {
      List<String> row = rows.get(n);
      if (row.stream().allMatch(String::isBlank)) continue;
      try {
        out.add(record(row, col));
      } catch (IllegalArgumentException e) {
        errors.add(new RowError(n + 1, e.getMessage()));
      }
    }
    return new Result(out, errors);
  }

  private static String key(String header) { return header.replace("﻿", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

  private static String cell(List<String> row, Map<String, Integer> col, String... names) {
    for (String n : names) {
      Integer i = col.get(n);
      if (i != null && i < row.size()) return PaytmValues.text(row.get(i));
    }
    return null;
  }

  private PaytmSettlementRecord record(List<String> row, Map<String, Integer> col) {
    String txn = cell(row, col, "transactionid");
    if (txn == null) throw new IllegalArgumentException("Transaction ID is empty");
    String status = cell(row, col, "status");
    Map<String, String> detail = new LinkedHashMap<>();
    String updated = cell(row, col, "updateddate");
    if (updated != null) detail.put("updatedDate", PaytmValues.dateTime(updated).toString());
    String mode = cell(row, col, "paymentmode");
    if (mode != null && "OTHER".equals(PaytmValues.mode(mode))) detail.put("paymentModeRaw", mode);
    return new PaytmSettlementRecord("paytm", "CSV", txn, cell(row, col, "orderid"), PaytmValues.type(cell(row, col, "transactiontype")),
        status == null ? null : status.toUpperCase(Locale.ROOT), PaytmValues.mode(mode), null, null, null, null, null, null, null,
        PaytmValues.paise(cell(row, col, "amount")), PaytmValues.paise(cell(row, col, "settledamount")), PaytmValues.paise(cell(row, col, "commission")),
        PaytmValues.paise(cell(row, col, "gst")), null, cell(row, col, "utrno", "utr"), PaytmValues.dateTime(cell(row, col, "transactiondate")),
        PaytmValues.date(cell(row, col, "settleddate", "settlementdate")), cell(row, col, "splitflag"), null, null, detail);
  }

  /** Splits CSV text into rows of fields: quotes, doubled quotes, commas and line breaks inside quotes, CRLF, and a leading BOM. */
  static List<List<String>> rows(String text) {
    String t = text.startsWith("﻿") ? text.substring(1) : text;
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder f = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
            f.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          f.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        row.add(f.toString());
        f.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') i++;
        row.add(f.toString());
        f.setLength(0);
        rows.add(row);
        row = new ArrayList<>();
      } else {
        f.append(c);
      }
    }
    if (f.length() > 0 || !row.isEmpty()) {
      row.add(f.toString());
      rows.add(row);
    }
    return rows;
  }
}
