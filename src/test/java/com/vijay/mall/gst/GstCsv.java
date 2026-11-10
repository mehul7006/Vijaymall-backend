package com.vijay.mall.gst;

import com.vijay.mall.gst.domain.GstRow;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test helper: reads the three delivered CSV files into the same records the repository returns from the database, so the
 * matcher test needs no database. Folder: the system property {@code gst.csv.dir}, else the classpath folder {@code /gst/}.
 */
public final class GstCsv {
  private GstCsv() {}

  public static List<GstRow.Hsn> hsn() {
    List<GstRow.Hsn> out = new ArrayList<>();
    for (Map<String, String> r : rows("01_official_gst_hsn_master.csv")) {
      String igst = r.get("igst_rate");
      out.add(new GstRow.Hsn(UUID.nameUUIDFromBytes(("hsn:" + r.get("hsn_id")).getBytes(StandardCharsets.UTF_8)),
          Integer.parseInt(r.get("hsn_id")), r.get("hsn_code").strip(), r.get("chapter"), r.get("tariff_description"),
          r.get("product_description"), "", r.get("classification_conditions"),
          r.get("condition_key").isBlank() ? "ANY" : r.get("condition_key"), r.get("condition_label"),
          new BigDecimal(igst), new BigDecimal(r.get("cgst_rate")), new BigDecimal(r.get("sgst_rate")),
          new BigDecimal(igst), new BigDecimal(r.get("cess_rate")), r.get("tax_type"), r.get("schedule"),
          r.get("notification_reference"), r.get("source_reference"), LocalDate.parse(r.get("effective_from")),
          r.get("verification_status"), "", true, Instant.EPOCH));
    }
    return out;
  }

  public static List<GstRow.Keyword> keywords() {
    List<GstRow.Keyword> out = new ArrayList<>();
    for (Map<String, String> r : rows("02_supermarket_product_keywords.csv")) {
      out.add(new GstRow.Keyword(UUID.nameUUIDFromBytes(("kw:" + r.get("keyword_id")).getBytes(StandardCharsets.UTF_8)),
          Integer.parseInt(r.get("keyword_id")), r.get("product_name").strip(), "", r.get("search_keywords").strip(),
          r.get("suggested_hsn").strip(), r.get("condition_key").strip(), r.get("department").strip(),
          r.get("subcategory").strip(), r.get("verification_status")));
    }
    return out;
  }

  /** The lines of a test resource that are not comments, as tab-separated fields. */
  public static List<String[]> tsv(String resource) {
    List<String[]> out = new ArrayList<>();
    try (InputStream in = GstCsv.class.getResourceAsStream("/gst/" + resource)) {
      if (in == null) throw new IllegalStateException("test resource /gst/" + resource + " not found");
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
        if (line.isBlank() || line.startsWith("#")) continue;
        out.add(line.split("\t", -1));
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private static List<Map<String, String>> rows(String file) {
    String text;
    try {
      String dir = System.getProperty("gst.csv.dir");
      if (dir != null && !dir.isBlank()) {
        text = Files.readString(Path.of(dir).resolve(file), StandardCharsets.UTF_8);
      } else {
        try (InputStream in = GstCsv.class.getResourceAsStream("/gst/" + file)) {
          if (in == null) {
            throw new IllegalStateException("Put the three CSV files in src/test/resources/gst/ or run with -Dgst.csv.dir=<folder>; missing " + file);
          }
          text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + file + ": " + e.getMessage(), e);
    }
    return parse(text);
  }

  /** Small RFC 4180 reader (quoted cells, doubled quotes, newlines inside quotes); the first non-blank line is the header. */
  static List<Map<String, String>> parse(String text) {
    List<List<String>> lines = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    String src = text.startsWith("﻿") ? text.substring(1) : text;
    for (int i = 0; i < src.length(); i++) {
      char ch = src.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < src.length() && src.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cell.append(ch);
        }
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == ',') {
        row.add(cell.toString());
        cell.setLength(0);
      } else if (ch == '\n') {
        row.add(cell.toString());
        cell.setLength(0);
        if (row.stream().anyMatch(c -> !c.isBlank())) lines.add(row);
        row = new ArrayList<>();
      } else if (ch != '\r') {
        cell.append(ch);
      }
    }
    if (cell.length() > 0 || !row.isEmpty()) {
      row.add(cell.toString());
      if (row.stream().anyMatch(c -> !c.isBlank())) lines.add(row);
    }
    List<Map<String, String>> out = new ArrayList<>();
    List<String> head = lines.get(0).stream().map(String::strip).toList();
    for (int n = 1; n < lines.size(); n++) {
      Map<String, String> m = new HashMap<>();
      for (int i = 0; i < head.size(); i++) m.put(head.get(i), i < lines.get(n).size() ? lines.get(n).get(i) : "");
      out.add(m);
    }
    return out;
  }
}
