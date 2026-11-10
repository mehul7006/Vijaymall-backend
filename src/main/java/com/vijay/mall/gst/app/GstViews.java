package com.vijay.mall.gst.app;

import com.vijay.mall.gst.domain.GstMatcher;
import com.vijay.mall.gst.domain.GstRow;
import java.math.BigDecimal;

/** Maps master rows to the JSON shapes of the API contract. */
final class GstViews {
  private GstViews() {}

  /** A name match: the row with the item that matched, its 0 to 1 score (two decimals) and the verification to show. */
  static GstService.RowView hit(GstMatcher.Hit h) { return row(h.row(), h.matchedName(), Math.round(h.score() * 100) / 100.0, h.verification()); }

  /** A list row: no match fields. */
  static GstService.RowView row(GstRow.Hsn h) { return row(h, null, null, h.verification()); }

  static GstService.RowView row(GstRow.Hsn h, String matchedName, Double score, String verification) {
    return new GstService.RowView(h.id(), h.hsn(), h.chapter(), h.description(), h.tariffDescription(), h.productDescription(), h.nameGu(),
        h.classificationConditions(), clean(h.gstPercent()), clean(h.igstPercent()), clean(h.cgstPercent()), clean(h.sgstPercent()),
        clean(h.cessPercent()), h.taxType(), h.conditionKey(), h.conditionLabel(), clean(h.priceMin()), clean(h.priceMax()),
        BillingRateRule.accepts(h.gstPercent()), matchedName, score, verification, h.schedule(), h.notificationReference(),
        h.sourceReference(), h.effectiveFrom(), h.note(), h.active(), h.updatedAt());
  }

  static GstService.RateView rate(GstRow.Rate r) {
    return new GstService.RateView(r.code(), clean(r.gstPercent()), clean(r.cgstPercent()), clean(r.sgstPercent()), clean(r.igstPercent()),
        clean(r.cessPercent()), r.taxType(), BillingRateRule.accepts(r.gstPercent()), r.note());
  }

  /** 5.000 gives 5, 2.500 gives 2.5, 0.000 gives 0 and 100 stays 100 (never 1E+2): the contract promises plain JSON numbers. */
  static BigDecimal clean(BigDecimal v) {
    if (v == null) return null;
    BigDecimal s = v.stripTrailingZeros();
    return s.scale() < 0 ? s.setScale(0) : s;
  }
}
