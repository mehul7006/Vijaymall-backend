package com.vijay.mall.gst.app;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.gst.GstCsv;
import com.vijay.mall.gst.domain.GstRow;
import com.vijay.mall.gst.infra.GstRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test data for the gst service tests: the delivered CSV rows behind a mocked repository. */
final class GstFixtures {
  private GstFixtures() {}

  static GstRow.Rate rate(String code, String percent, String cgst, String taxType) {
    return new GstRow.Rate(UUID.randomUUID(), code, 1, new BigDecimal(percent), new BigDecimal(cgst), new BigDecimal(cgst), new BigDecimal(percent),
        BigDecimal.ZERO, taxType, "", true);
  }

  static List<GstRow.Rate> rates() {
    return List.of(rate("GST_0", "0.000", "0.000", "NIL"), rate("GST_0_25", "0.250", "0.125", "TAXABLE"), rate("GST_1_5", "1.500", "0.750", "TAXABLE"),
        rate("GST_5", "5.000", "2.500", "TAXABLE"), rate("GST_40", "40.000", "20.000", "TAXABLE"));
  }

  /** A repository mock that serves the full seed data, fingerprint {@code f1}. */
  static GstRepository repo() {
    GstRepository repo = mock(GstRepository.class);
    when(repo.fingerprint()).thenReturn("f1");
    when(repo.allHsn()).thenReturn(GstCsv.hsn());
    when(repo.activeKeywords()).thenReturn(GstCsv.keywords());
    when(repo.activeRates()).thenReturn(rates());
    return repo;
  }

  static GstRow.Hsn hsn(String code, int rowNo, String condition, String percent, String verification) {
    return new GstRow.Hsn(UUID.randomUUID(), rowNo, code, code.substring(0, 2), "tariff " + code, "Product " + code, "", "", condition, condition,
        new BigDecimal(percent), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(percent), BigDecimal.ZERO, "TAXABLE", "", "", "",
        LocalDate.of(2025, 9, 22), verification, "", true, Instant.EPOCH);
  }

  static GstRow.Keyword keyword(int rowNo, String name, String keywords, String code, String verification) {
    return new GstRow.Keyword(UUID.randomUUID(), rowNo, name, "", keywords, code, "", "", "", verification);
  }

  static <T> List<T> plus(List<T> list, T extra) {
    List<T> out = new ArrayList<>(list);
    out.add(extra);
    return out;
  }
}
