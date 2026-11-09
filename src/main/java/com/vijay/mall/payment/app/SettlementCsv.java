package com.vijay.mall.payment.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.web.RequestInstants;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Reads an acquirer or bank statement CSV into rows, using the column names the admin maps. Pure: no database, no clock. */
final class SettlementCsv {
  /** Largest file accepted. */
  static final int MAX_BYTES = 2 * 1024 * 1024;
  /** Most data rows accepted in one file. */
  static final int MAX_ROWS = 5000;
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private SettlementCsv() {}

  /** One statement row; {@code line} is its row number in the file (the header is row 1). */
  record Row(int line, Instant at, String reference, long amountPaise, long feePaise, long taxPaise, String description) {}

  /** The rows, or {@link ApiException} 400 {@code CSV_INVALID} listing the first problems by line. */
  static List<Row> parse(byte[] bytes, SettlementService.CsvMapping m) {
    if (bytes.length > MAX_BYTES) throw ApiException.fieldError("CSV_INVALID", "file", "larger than 2 MB");
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.startsWith("﻿")) text = text.substring(1);
    char delimiter = m.delimiter() == null || m.delimiter().isEmpty() ? ',' : "tab".equalsIgnoreCase(m.delimiter()) ? '\t' : m.delimiter().charAt(0);
    List<List<String>> records = records(text, delimiter);
    if (records.isEmpty()) throw ApiException.fieldError("CSV_INVALID", "file", "the file is empty");
    Map<String, Integer> header = new HashMap<>();
    List<String> head = records.get(0);
    for (int i = 0; i < head.size(); i++) header.putIfAbsent(head.get(i).strip().toLowerCase(Locale.ROOT), i);
    int date = column(header, m.date(), "date", "dateColumn", true);
    int ref = column(header, m.reference(), "reference", "referenceColumn", true);
    int amount = column(header, m.amount(), "amount", "amountColumn", true);
    int fee = column(header, m.fee(), "fee", "feeColumn", false);
    int tax = column(header, m.tax(), "tax", "taxColumn", false);
    int desc = column(header, m.description(), "description", "descriptionColumn", false);
    if (records.size() - 1 > MAX_ROWS) throw ApiException.fieldError("CSV_INVALID", "file", "more than " + MAX_ROWS + " rows: split the file");
    boolean paise = "PAISE".equalsIgnoreCase(m.amountUnit());
    DateTimeFormatter fmt = m.dateFormat() == null || m.dateFormat().isBlank() ? null : formatter(m.dateFormat());
    List<Row> rows = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    for (int r = 1; r < records.size(); r++) {
      List<String> f = records.get(r);
      int line = r + 1;
      try {
        long a = money(cell(f, amount), paise, true);
        rows.add(new Row(line, date(cell(f, date), fmt), cell(f, ref).strip(), a, fee < 0 ? 0 : Math.abs(money(cell(f, fee), paise, false)),
            tax < 0 ? 0 : Math.abs(money(cell(f, tax), paise, false)), desc < 0 ? "" : cell(f, desc).strip()));
      } catch (IllegalArgumentException | DateTimeException e) {
        if (problems.size() < 20) problems.add("row " + line + ": " + e.getMessage());
      }
    }
    if (!problems.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "CSV_INVALID", "The file has rows that cannot be read",
          Map.of("fields", Map.of("file", problems.get(0)), "problems", problems));
    }
    if (rows.isEmpty()) throw ApiException.fieldError("CSV_INVALID", "file", "the file has no data rows");
    return rows;
  }

  private static DateTimeFormatter formatter(String pattern) {
    try {
      return DateTimeFormatter.ofPattern(pattern);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("dateFormat", "not a valid date pattern");
    }
  }

  private static int column(Map<String, Integer> header, String given, String fallback, String field, boolean required) {
    String name = given == null || given.isBlank() ? fallback : given.strip().toLowerCase(Locale.ROOT);
    Integer i = header.get(name);
    if (i == null && required) throw ApiException.validation(field, "no column named \"" + name + "\" in the file");
    return i == null ? -1 : i;
  }

  private static String cell(List<String> f, int i) { return i < 0 || i >= f.size() ? "" : f.get(i); }

  private static Instant date(String text, DateTimeFormatter fmt) {
    if (text.isBlank()) throw new IllegalArgumentException("date is empty");
    if (fmt == null) return RequestInstants.parse(text);
    return LocalDate.parse(text.strip(), fmt).atStartOfDay(INDIA).toInstant();
  }

  /** Reads "1,234.50", "Rs 99", "-20" as paise; a blank optional cell is zero. */
  private static long money(String text, boolean paise, boolean required) {
    String t = text.strip().replace(",", "").replace("₹", "").replace("Rs.", "").replace("Rs", "").strip();
    if (t.isEmpty()) {
      if (required) throw new IllegalArgumentException("amount is empty");
      return 0;
    }
    BigDecimal v;
    try {
      v = new BigDecimal(t);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("\"" + text.strip() + "\" is not an amount");
    }
    BigDecimal p = paise ? v : v.movePointRight(2);
    try {
      return p.longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("\"" + text.strip() + "\" has more decimals than paise");
    }
  }

  /** RFC 4180 reader: quoted cells may hold the delimiter, line breaks and doubled quotes. Blank lines are skipped. */
  static List<List<String>> records(String text, char delimiter) {
    List<List<String>> out = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    boolean any = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          cell.append(c);
        }
      } else if (c == '"') {
        quoted = true;
        any = true;
      } else if (c == delimiter) {
        row.add(cell.toString());
        cell.setLength(0);
        any = true;
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        if (any || cell.length() > 0) {
          row.add(cell.toString());
          out.add(row);
        }
        row = new ArrayList<>();
        cell.setLength(0);
        any = false;
      } else {
        cell.append(c);
      }
    }
    if (any || cell.length() > 0) {
      row.add(cell.toString());
      out.add(row);
    }
    return out;
  }
}
