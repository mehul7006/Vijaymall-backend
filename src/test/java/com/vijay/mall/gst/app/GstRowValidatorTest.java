package com.vijay.mall.gst.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.gst.domain.GstRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GstRowValidatorTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T06:00:00Z"), ZoneOffset.UTC);

  private static GstRowInput input(String hsn, String description, String percent, String taxType) {
    return new GstRowInput(hsn, description, null, percent == null ? null : new BigDecimal(percent), null, taxType, null, null, null, null, null, null, null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> fieldsOf(GstRowInput in, GstRow.Hsn old) {
    ApiException e = (ApiException) catchThrowable(() -> GstRowValidator.build(UUID.randomUUID(), 1, in, old, CLOCK));
    assertThat(e).isNotNull();
    assertThat(e.status().value()).isEqualTo(400);
    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
    return (Map<String, Object>) e.details().get("fields");
  }

  @Test void aMinimalCreateTakesTheDefaultsAndSplitsTheRateInHalf() {
    GstRowValidator.Built b = GstRowValidator.build(UUID.randomUUID(), 0, input(" 7777 ", " Test snack ", "5", "taxable"), null, CLOCK);
    GstRow.Hsn h = b.row();
    assertThat(h.hsn()).isEqualTo("7777");
    assertThat(h.chapter()).isEqualTo("77");
    assertThat(h.productDescription()).isEqualTo("Test snack");
    assertThat(h.taxType()).isEqualTo("TAXABLE");
    assertThat(h.conditionKey()).isEqualTo("ANY");
    assertThat(h.verification()).isEqualTo("VERIFY_PRODUCT");
    assertThat(h.effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 10));
    assertThat(h.cgstPercent()).isEqualByComparingTo("2.5");
    assertThat(h.sgstPercent()).isEqualByComparingTo("2.5");
    assertThat(h.igstPercent()).isEqualByComparingTo("5");
    assertThat(h.cessPercent()).isEqualByComparingTo("0");
    assertThat(h.active()).isTrue();
    assertThat(b.keywords()).isEmpty();
  }

  @Test void aQuarterPercentSplitsIntoAnExactEighthPair() {
    GstRow.Hsn h = GstRowValidator.build(UUID.randomUUID(), 0, input("7777", "x", "0.25", "TAXABLE"), null, CLOCK).row();
    assertThat(h.cgstPercent()).isEqualByComparingTo("0.125");
    assertThat(h.cgstPercent().add(h.sgstPercent())).isEqualByComparingTo(h.igstPercent());
  }

  @Test void everyWrongFieldIsReportedTogether() {
    GstRowInput in = new GstRowInput("12", " ", "x".repeat(301), new BigDecimal("101"), new BigDecimal("-1"), "ZERO", "bad key!", "x".repeat(121), "MAYBE",
        "x".repeat(201), null, "x".repeat(501), List.of("ok", "x".repeat(121)));
    assertThat(fieldsOf(in, null)).containsOnlyKeys("hsn", "description", "nameGu", "gstPercent", "cessRate", "taxType", "condition",
        "conditionLabel", "verification", "notificationReference", "note", "keywords");
  }

  @Test void rateRulesRejectTooManyDecimalsMissingRatesAndNonTaxableWithARate() {
    assertThat(fieldsOf(input("1006", "Rice", "5.555", "TAXABLE"), null)).containsOnlyKeys("gstPercent");
    assertThat(fieldsOf(input("1006", "Rice", "0.125", "TAXABLE"), null)).containsOnlyKeys("gstPercent");
    assertThat(fieldsOf(input("1006", "Rice", "-1", "TAXABLE"), null)).containsOnlyKeys("gstPercent");
    assertThat(fieldsOf(input("1006", "Rice", null, "TAXABLE"), null)).containsOnlyKeys("gstPercent");
    assertThat(fieldsOf(input("1006", "Rice", "5", "NIL"), null)).containsEntry("gstPercent", "NIL rows must have rate 0");
    assertThat(fieldsOf(input("1006", "Rice", "5", "EXEMPT"), null)).containsOnlyKeys("gstPercent");
    assertThat(fieldsOf(input("1006", null, "5", null), null)).containsOnlyKeys("description", "taxType");
    GstRow.Hsn nil = GstRowValidator.build(UUID.randomUUID(), 0, input("1006", "Rice", "0", "nil"), null, CLOCK).row();
    assertThat(nil.taxType()).isEqualTo("NIL");
  }

  @Test void tooManyKeywordsAreRejectedAndBlankOnesSkipped() {
    List<String> many = Collections.nCopies(51, "name");
    GstRowInput in = new GstRowInput("1006", "Rice", null, BigDecimal.ONE, null, "TAXABLE", null, null, null, null, null, null, many);
    assertThat(fieldsOf(in, null)).containsOnlyKeys("keywords");
    List<String> names = new ArrayList<>();
    names.add(" my snack ");
    names.add("  ");
    names.add(null);
    GstRowInput ok = new GstRowInput("1006", "Rice", null, BigDecimal.ONE, null, "TAXABLE", null, null, null, null, null, null, names);
    assertThat(GstRowValidator.build(UUID.randomUUID(), 0, ok, null, CLOCK).keywords()).containsExactly("my snack");
  }

  @Test void anUpdateKeepsTheStoredValuesOfFieldsThatWereNotSent() {
    GstRow.Hsn old = new GstRow.Hsn(UUID.randomUUID(), 7, "1006", "10", "Cereals", "Rice", "ચોખા", "cond text", "LOOSE", "Loose", new BigDecimal("0"),
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1.5"), "NIL", "Sch I", "notif", "src", LocalDate.of(2025, 1, 2),
        "VERIFIED", "keep me", false, Instant.EPOCH);
    GstRow.Hsn h = GstRowValidator.build(old.id(), old.rowNo(), input("1006", "Rice new", "0", "NIL"), old, CLOCK).row();
    assertThat(h.conditionKey()).isEqualTo("LOOSE");
    assertThat(h.conditionLabel()).isEqualTo("Loose");
    assertThat(h.verification()).isEqualTo("VERIFIED");
    assertThat(h.nameGu()).isEqualTo("ચોખા");
    assertThat(h.notificationReference()).isEqualTo("notif");
    assertThat(h.note()).isEqualTo("keep me");
    assertThat(h.effectiveFrom()).isEqualTo(LocalDate.of(2025, 1, 2));
    assertThat(h.cessPercent()).isEqualByComparingTo("1.5");
    assertThat(h.chapter()).isEqualTo("10");
    assertThat(h.tariffDescription()).isEqualTo("Cereals");
    assertThat(h.schedule()).isEqualTo("Sch I");
    assertThat(h.sourceReference()).isEqualTo("src");
    assertThat(h.classificationConditions()).isEqualTo("cond text");
    assertThat(h.productDescription()).isEqualTo("Rice new");
    assertThat(h.active()).as("saving switches the row back on").isTrue();
  }

  @Test void sentFieldsReplaceTheStoredOnesAndAChangedCodeChangesTheChapter() {
    GstRow.Hsn old = new GstRow.Hsn(UUID.randomUUID(), 7, "1006", "10", "Cereals", "Rice", "", "", "LOOSE", "Loose", BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "NIL", "", "", "", LocalDate.of(2025, 1, 2), "VERIFIED", "", true, Instant.EPOCH);
    GstRowInput in = new GstRowInput("110100", "Wheat flour", "લોટ", new BigDecimal("5"), new BigDecimal("0.5"), "TAXABLE", "packed_labelled",
        "Packed", "VERIFY_PRODUCT", "09/2025", LocalDate.of(2026, 1, 1), "n", null);
    GstRow.Hsn h = GstRowValidator.build(old.id(), old.rowNo(), in, old, CLOCK).row();
    assertThat(h.hsn()).isEqualTo("110100");
    assertThat(h.chapter()).isEqualTo("11");
    assertThat(h.conditionKey()).isEqualTo("PACKED_LABELLED");
    assertThat(h.conditionLabel()).isEqualTo("Packed");
    assertThat(h.verification()).isEqualTo("VERIFY_PRODUCT");
    assertThat(h.nameGu()).isEqualTo("લોટ");
    assertThat(h.cessPercent()).isEqualByComparingTo("0.5");
    assertThat(h.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
  }

  @Test void aBadUpdateStillThrowsOnlyApiExceptions() {
    assertThatThrownBy(() -> GstRowValidator.build(UUID.randomUUID(), 0, input("x", "d", "5", "TAXABLE"), null, CLOCK)).isInstanceOf(ApiException.class);
  }
}
