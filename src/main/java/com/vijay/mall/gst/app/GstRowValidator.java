package com.vijay.mall.gst.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.gst.domain.GstRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Turns an admin {@link GstRowInput} into a row, reporting every wrong field in one {@code 400 VALIDATION_FAILED}
 * ({@code details.fields}), not just the first.
 */
final class GstRowValidator {
  static final Pattern HSN = Pattern.compile("^(\\d{4}|\\d{6}|\\d{8})$");
  private static final Pattern CONDITION = Pattern.compile("^[A-Z0-9_]{1,40}$");
  static final Set<String> TAX_TYPES = Set.of("TAXABLE", "NIL", "EXEMPT", "NON_GST");
  static final Set<String> VERIFICATION = Set.of("VERIFIED", "VERIFY_PRODUCT");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  static final int MAX_KEYWORDS = 50;
  static final int MAX_KEYWORD_LENGTH = 120;

  /** A checked input: the row to store and the item names to add under its code. */
  record Built(GstRow.Hsn row, List<String> keywords) {}

  private GstRowValidator() {}

  /** The row to store, or an {@link ApiException} naming every invalid field. {@code old} is null on create. */
  static Built build(UUID id, int rowNo, GstRowInput in, GstRow.Hsn old, Clock clock) {
    Map<String, Object> bad = new LinkedHashMap<>();
    String hsn = text(in.hsn());
    if (!HSN.matcher(hsn).matches()) bad.put("hsn", "must be 4, 6 or 8 digits");
    String descr = text(in.description());
    if (descr.isEmpty() || descr.length() > 300) bad.put("description", "must be 1 to 300 characters");
    BigDecimal gst = in.gstPercent();
    if (gst == null || gst.signum() < 0 || gst.compareTo(HUNDRED) > 0 || gst.stripTrailingZeros().scale() > 2) {
      bad.put("gstPercent", "must be 0 to 100 with at most 2 decimals");
      gst = null;
    }
    BigDecimal cess = in.cessRate() != null ? in.cessRate() : old != null ? old.cessPercent() : BigDecimal.ZERO;
    if (cess.signum() < 0 || cess.compareTo(HUNDRED) > 0 || cess.stripTrailingZeros().scale() > 3) {
      bad.put("cessRate", "must be 0 to 100 with at most 3 decimals");
    }
    String tax = text(in.taxType()).toUpperCase(Locale.ROOT);
    if (!TAX_TYPES.contains(tax)) bad.put("taxType", "must be one of TAXABLE, NIL, EXEMPT, NON_GST");
    else if (gst != null && !tax.equals("TAXABLE") && gst.signum() != 0) bad.put("gstPercent", tax + " rows must have rate 0");
    String cond = blank(in.condition()) ? (old != null ? old.conditionKey() : "ANY") : text(in.condition()).toUpperCase(Locale.ROOT);
    if (!CONDITION.matcher(cond).matches()) bad.put("condition", "must be capital letters, digits and _ (1 to 40)");
    String label = in.conditionLabel() != null ? text(in.conditionLabel()) : old != null ? old.conditionLabel() : "";
    if (label.length() > 120) bad.put("conditionLabel", "must be at most 120 characters");
    String ver = blank(in.verification()) ? (old != null ? old.verification() : "VERIFY_PRODUCT") : text(in.verification());
    if (!VERIFICATION.contains(ver)) bad.put("verification", "must be VERIFIED or VERIFY_PRODUCT");
    String nameGu = in.nameGu() != null ? text(in.nameGu()) : old != null ? old.nameGu() : "";
    if (nameGu.length() > 300) bad.put("nameGu", "must be at most 300 characters");
    String notification = in.notificationReference() != null ? text(in.notificationReference()) : old != null ? old.notificationReference() : "";
    if (notification.length() > 200) bad.put("notificationReference", "must be at most 200 characters");
    String note = in.note() != null ? text(in.note()) : old != null ? old.note() : "";
    if (note.length() > 500) bad.put("note", "must be at most 500 characters");
    List<String> names = keywordNames(in.keywords(), bad);
    if (!bad.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please check the highlighted fields", Map.of("fields", bad));

    BigDecimal half = gst.divide(BigDecimal.valueOf(2));
    LocalDate from = in.effectiveFrom() != null ? in.effectiveFrom() : old != null ? old.effectiveFrom() : LocalDate.now(clock);
    boolean sameCode = old != null && old.hsn().equals(hsn);
    GstRow.Hsn row = new GstRow.Hsn(id, rowNo, hsn, sameCode ? old.chapter() : hsn.substring(0, 2), old == null ? "" : old.tariffDescription(),
        descr, nameGu, old == null ? "" : old.classificationConditions(), cond, label, gst, half, half, gst, cess, tax,
        old == null ? "" : old.schedule(), notification, old == null ? "" : old.sourceReference(), from, ver, note, true, clock.instant());
    return new Built(row, names);
  }

  /** The new item names of an input, trimmed and without blanks; problems are added to {@code bad} under {@code keywords}. */
  private static List<String> keywordNames(List<String> raw, Map<String, Object> bad) {
    List<String> out = new ArrayList<>();
    if (raw == null) return out;
    if (raw.size() > MAX_KEYWORDS) bad.put("keywords", "at most " + MAX_KEYWORDS + " names");
    for (String k : raw) {
      String name = text(k);
      if (name.isEmpty()) continue;
      if (name.length() > MAX_KEYWORD_LENGTH) bad.put("keywords", "each name is at most " + MAX_KEYWORD_LENGTH + " characters");
      out.add(name);
    }
    return out;
  }

  private static boolean blank(String s) { return s == null || s.isBlank(); }

  private static String text(String s) { return s == null ? "" : s.strip(); }
}
