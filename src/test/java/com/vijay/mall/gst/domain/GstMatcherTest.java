package com.vijay.mall.gst.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vijay.mall.gst.GstCsv;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Plain unit test of the matcher on the delivered data (no Spring, no database). The table-driven cases live in
 * {@code src/test/resources/gst/cases.tsv}; the Python port {@code verify_matcher.py --check} runs the same file.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GstMatcherTest {
  private GstMatcher matcher;
  private List<GstRow.Hsn> hsn;
  private List<GstRow.Keyword> keywords;

  @BeforeAll
  void load() {
    hsn = GstCsv.hsn();
    keywords = GstCsv.keywords();
    matcher = new GstMatcher(hsn, keywords);
  }

  static Stream<Arguments> cases() { return GstCsv.tsv("cases.tsv").stream().map(f -> Arguments.of((Object[]) f)); }

  /** basmati rice, atta, toor dal, haldi, ghee, uht milk, paneer, butter, biscuits, chocolate, shampoo, t-shirt prices, salt, potato, typos ... */
  @ParameterizedTest(name = "{0} / cat={1} / price={2} / packed={3} -> {4} {5} {6}%")
  @MethodSource("cases")
  void tableOfCases(String name, String category, String price, String packed, String hsnCode, String condition,
                    String rate, String choices) {
    GstMatcher.Result r = matcher.suggest(name, "-".equals(category) ? null : category,
        "-".equals(price) ? null : new BigDecimal(price), "-".equals(packed) ? null : Boolean.valueOf(packed), 5);
    if ("NONE".equals(hsnCode)) {
      assertNull(r.best(), "expected no match for '" + name + "'");
      assertTrue(r.alternatives().isEmpty());
      return;
    }
    GstMatcher.Hit best = r.best();
    assertNotNull(best, "no match for '" + name + "'");
    assertEquals(hsnCode, best.row().hsn(), "hsn for '" + name + "'");
    assertEquals(condition, best.row().conditionKey(), "condition for '" + name + "'");
    assertEquals(0, new BigDecimal(rate).compareTo(best.row().gstPercent()), "rate for '" + name + "'");
    assertTrue(best.score() >= 0.5 && best.score() <= 1.0, "score " + best.score());
    Set<String> got = new HashSet<>();
    r.choices().forEach(c -> got.add(c.row().conditionKey()));
    Set<String> want = "-".equals(choices) ? Set.of() : Set.of(choices.split(","));
    assertEquals(want, got, "choices for '" + name + "'");
    r.choices().forEach(c -> assertEquals(best.row().hsn(), c.row().hsn(), "choices stay inside the same HSN"));
  }

  @Test
  void dataLoadedAsDelivered() {
    assertEquals(564, matcher.hsnRowCount());
    assertEquals(5950, matcher.keywordCount());
  }

  @Test
  void everyKeywordPointsToAnExistingHsn() {
    Set<String> codes = new HashSet<>();
    hsn.forEach(h -> codes.add(h.hsn()));
    List<String> orphans = new ArrayList<>();
    keywords.stream().filter(k -> !codes.contains(k.hsn())).forEach(k -> orphans.add(k.rowNo() + " " + k.name()));
    assertTrue(orphans.isEmpty(), "keywords without an HSN row: " + orphans);
  }

  @Test
  void priceBandEdgeIsInclusiveAtTwoThousandFiveHundred() {
    assertEquals("PRICE_UPTO_2500", matcher.suggest("t shirt", null, new BigDecimal("2500"), null, 1).best().row().conditionKey());
    assertEquals("PRICE_ABOVE_2500", matcher.suggest("t shirt", null, new BigDecimal("2500.01"), null, 1).best().row().conditionKey());
  }

  @Test
  void alternativesAreOnePerHsnAndNeverRepeatTheBest() {
    GstMatcher.Result r = matcher.suggest("milk", null, null, null, 5);
    assertNotNull(r.best());
    Set<String> seen = new HashSet<>();
    seen.add(r.best().row().hsn());
    for (GstMatcher.Hit a : r.alternatives()) assertTrue(seen.add(a.row().hsn()), "duplicate HSN " + a.row().hsn());
    assertTrue(r.alternatives().size() <= 4);
    assertTrue(r.alternatives().stream().allMatch(a -> a.score() >= 0.5));
  }

  @Test
  void limitCapsBestPlusAlternatives() {
    assertTrue(matcher.suggest("milk", null, null, null, 1).alternatives().isEmpty());
    assertTrue(matcher.suggest("milk", null, null, null, 3).alternatives().size() <= 2);
  }

  @Test
  void choicesNeverContainTheBest() {
    GstMatcher.Result r = matcher.suggest("toor dal", null, null, null, 5);
    r.choices().forEach(c -> assertFalse(c.row().id().equals(r.best().row().id())));
    assertEquals(1, r.choices().size());
  }

  @Test
  void nothingBelowThresholdIsReturned() {
    for (String junk : List.of("xyzzy", "qqqq wwww", "zzzzzzzz", "")) {
      GstMatcher.Result r = matcher.suggest(junk, null, null, null, 5);
      assertNull(r.best(), junk);
      assertTrue(r.alternatives().isEmpty());
    }
  }

  @Test
  void packSizesAndNumbersAreIgnoredButTheNameStillMatches() {
    assertEquals("1006", matcher.suggest("basmati rice 25kg", null, null, null, 1).best().row().hsn());
    assertEquals("1006", matcher.suggest("BASMATI  RICE 1 KG 500 gm", null, null, null, 1).best().row().hsn());
  }

  @Test
  void verificationIsTheCautiousOneOfRowAndKeyword() {
    GstMatcher.Hit h = matcher.suggest("basmati rice", null, null, null, 1).best();
    assertEquals("VERIFIED", h.verification());
    boolean anyCautious = false;
    java.util.Map<String, Integer> nameCount = new java.util.HashMap<>();
    keywords.forEach(k -> nameCount.merge(k.name().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum));
    for (GstRow.Keyword k : keywords) {
      if ("VERIFY_PRODUCT".equals(k.verification()) && nameCount.get(k.name().toLowerCase(java.util.Locale.ROOT)) == 1) {
        GstMatcher.Hit x = matcher.suggest(k.name(), null, null, null, 1).best();
        if (x != null && x.matchedName().equals(k.name())) {
          assertEquals("VERIFY_PRODUCT", x.verification(), k.name());
          anyCautious = true;
          break;
        }
      }
    }
    assertTrue(anyCautious, "expected at least one VERIFY_PRODUCT keyword in the data");
  }

  @Test
  void spellingFoldingAndTolerance() {
    assertEquals(List.of("dal"), GstMatcher.tokens("Daal"));
    assertEquals(List.of("atta"), GstMatcher.tokens("aata"));
    assertEquals(List.of("cumin"), GstMatcher.tokens("ZEERA"));
    assertEquals(List.of("rice", "basmati"), GstMatcher.tokens("rice, basmati!"));
    assertEquals(List.of(), GstMatcher.tokens("a 1"));
    String gujarati = "ગુજરાતી";
    assertEquals(List.of(gujarati), GstMatcher.tokens(gujarati), "Gujarati script is a word, not skipped");
  }

  @Test
  void levenshtein() {
    assertEquals(0, GstMatcher.distance("rice", "rice", 2));
    assertEquals(1, GstMatcher.distance("rice", "raice", 2));
    assertEquals(1, GstMatcher.distance("basmati", "basmatti", 2));
    assertEquals(2, GstMatcher.distance("shampoo", "shmpo", 2));
    assertEquals(3, GstMatcher.distance("abcd", "wxyz", 2), "stops early: max + 1");
    assertEquals(3, GstMatcher.distance("a", "abcde", 2), "length gap alone is enough");
  }

  @Test
  void searchByCodePrefixAndByText() {
    List<GstRow.Hsn> byCode = matcher.search("1006", 10);
    assertFalse(byCode.isEmpty());
    assertTrue(byCode.stream().allMatch(h -> h.hsn().startsWith("1006")));
    List<GstRow.Hsn> byText = matcher.search("basmati", 10);
    assertTrue(byText.stream().anyMatch(h -> h.hsn().equals("1006")));
    assertEquals(564, matcher.search("", 0).size());
  }

  @Test
  void inactiveRowsAreNotUsed() {
    List<GstRow.Hsn> rows = new ArrayList<>();
    for (GstRow.Hsn h : hsn) rows.add(h.hsn().equals("1006") ? withActive(h, false) : h);
    GstMatcher m = new GstMatcher(rows, keywords);
    assertTrue(m.rowsFor("1006").isEmpty());
    GstMatcher.Result r = m.suggest("basmati rice", null, null, null, 3);
    r.alternatives().forEach(a -> assertFalse(a.row().hsn().equals("1006")));
    if (r.best() != null) assertFalse(r.best().row().hsn().equals("1006"));
  }

  private static GstRow.Hsn withActive(GstRow.Hsn h, boolean active) {
    return new GstRow.Hsn(h.id(), h.rowNo(), h.hsn(), h.chapter(), h.tariffDescription(), h.productDescription(), h.nameGu(),
        h.classificationConditions(), h.conditionKey(), h.conditionLabel(), h.gstPercent(), h.cgstPercent(), h.sgstPercent(),
        h.igstPercent(), h.cessPercent(), h.taxType(), h.schedule(), h.notificationReference(), h.sourceReference(),
        h.effectiveFrom(), h.verification(), h.note(), active, h.updatedAt());
  }
}
