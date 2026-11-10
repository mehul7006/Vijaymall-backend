package com.vijay.mall.gst.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.gst.GstCsv;
import com.vijay.mall.gst.domain.GstRow;
import com.vijay.mall.gst.infra.GstRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The read side of the GST master on the delivered data, behind a mocked repository. */
class GstServiceTest {
  private final GstRepository repo = GstFixtures.repo();
  private final GstService service = new GstService(repo);

  private GstService loaded() {
    service.load();
    return service;
  }

  private static ApiException thrown(Runnable r) {
    return (ApiException) catchThrowable(r);
  }

  private static Throwable catchThrowable(Runnable r) {
    try {
      r.run();
      return null;
    } catch (RuntimeException e) {
      return e;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> fields(ApiException e) { return (Map<String, Object>) e.details().get("fields"); }

  // --- load and reload ------------------------------------------------------------------------------------------

  @Test void beforeTheFirstLoadReadsAnswer422AndDefaultsAreEmpty() {
    ApiException e = thrown(() -> service.suggest("rice", null, null, null, null));
    assertThat(e.code()).isEqualTo("GST_MASTER_LOADING");
    assertThat(e.status().value()).isEqualTo(422);
    assertThat(service.defaultsFor("basmati rice", null, null)).isEmpty();
  }

  @Test void aFailingFirstLoadIsLoggedNotThrownAndTheNextCheckRetries() {
    when(repo.allHsn()).thenThrow(new IllegalStateException("db down")).thenReturn(GstCsv.hsn());
    service.load();
    assertThat(thrown(() -> service.rates()).code()).isEqualTo("GST_MASTER_LOADING");
    service.reloadIfChanged();
    assertThat(service.rates().items()).hasSize(5);
  }

  @Test void theCheckDoesNothingWhileTheFingerprintIsUnchanged() {
    loaded();
    service.reloadIfChanged();
    service.reloadIfChanged();
    verify(repo, times(1)).allHsn();
  }

  @Test void aChangedFingerprintSwapsInTheNewIndexAtOnce() {
    loaded();
    assertThat(service.suggest("basmati rice", null, null, true, null).best().gstPercent()).isEqualByComparingTo("5");
    List<GstRow.Hsn> edited = new ArrayList<>();
    for (GstRow.Hsn h : GstCsv.hsn()) edited.add(h.hsn().equals("1006") && h.conditionKey().equals("PACKED_LABELLED") ? withRate(h, "12") : h);
    when(repo.fingerprint()).thenReturn("f2");
    when(repo.allHsn()).thenReturn(edited);
    service.reloadIfChanged();
    assertThat(service.suggest("basmati rice", null, null, true, null).best().gstPercent()).isEqualByComparingTo("12");
  }

  @Test void aFailingCheckKeepsTheLoadedMaster() {
    loaded();
    when(repo.fingerprint()).thenThrow(new IllegalStateException("db down"));
    service.reloadIfChanged();
    assertThat(service.suggest("basmati rice", null, null, null, null).best()).isNotNull();
  }

  private static GstRow.Hsn withRate(GstRow.Hsn h, String percent) {
    BigDecimal p = new BigDecimal(percent);
    return new GstRow.Hsn(h.id(), h.rowNo(), h.hsn(), h.chapter(), h.tariffDescription(), h.productDescription(), h.nameGu(),
        h.classificationConditions(), h.conditionKey(), h.conditionLabel(), p, BigDecimal.ZERO, BigDecimal.ZERO, p, h.cessPercent(), h.taxType(),
        h.schedule(), h.notificationReference(), h.sourceReference(), h.effectiveFrom(), h.verification(), h.note(), h.active(), h.updatedAt());
  }

  // --- suggest --------------------------------------------------------------------------------------------------

  @Test void suggestChecksEveryParameterAndNamesTheField() {
    loaded();
    assertThat(fields(thrown(() -> service.suggest(null, null, null, null, null)))).containsKey("name");
    assertThat(fields(thrown(() -> service.suggest("  ", null, null, null, null)))).containsKey("name");
    assertThat(fields(thrown(() -> service.suggest("x".repeat(121), null, null, null, null)))).containsKey("name");
    assertThat(fields(thrown(() -> service.suggest("rice", null, new BigDecimal("-1"), null, null)))).containsKey("price");
    assertThat(fields(thrown(() -> service.suggest("rice", null, new BigDecimal("10000001"), null, null)))).containsKey("price");
    assertThat(fields(thrown(() -> service.suggest("rice", null, null, null, 0)))).containsKey("limit");
    assertThat(fields(thrown(() -> service.suggest("rice", null, null, null, 11)))).containsKey("limit");
  }

  @Test void suggestKeepsTheBoundaryValuesAndTheDefaultLimit() {
    loaded();
    assertThat(service.suggest("rice", null, BigDecimal.ZERO, null, 1).alternatives()).isEmpty();
    assertThat(service.suggest("rice", null, new BigDecimal("10000000"), null, 10)).isNotNull();
    assertThat(service.suggest("milk", null, null, null, null).alternatives().size()).isLessThanOrEqualTo(4);
  }

  @Test void junkAndPackSizesGiveNoBest() {
    loaded();
    GstService.Suggestion s = service.suggest("xyzzy qwerty", null, null, null, null);
    assertThat(s.best()).isNull();
    assertThat(s.choices()).isEmpty();
    assertThat(s.alternatives()).isEmpty();
    assertThat(service.suggest("500 gm", null, null, null, null).best()).isNull();
  }

  @Test void aSuggestionCarriesTheContractFieldsAndRoundsTheScoreToTwoDecimals() {
    loaded();
    GstService.Suggestion s = service.suggest("basmati rice 5 kg", null, null, null, null);
    GstService.RowView best = s.best();
    assertThat(best.hsn()).isEqualTo("1006");
    assertThat(best.condition()).isEqualTo("PACKED_LABELLED");
    assertThat(best.gstPercent()).isEqualTo(new BigDecimal("5"));
    assertThat(best.cgstRate()).isEqualTo(new BigDecimal("2.5"));
    assertThat(best.acceptedForBilling()).isTrue();
    assertThat(best.matchedName()).isNotBlank();
    assertThat(best.score()).isBetween(0.5, 1.0);
    assertThat(best.score() * 100).isEqualTo((double) Math.round(best.score() * 100));
    assertThat(best.description()).isNotBlank();
    assertThat(s.choices()).extracting(GstService.RowView::condition).containsExactly("LOOSE");
    assertThat(s.choices()).extracting(GstService.RowView::rowId).doesNotContain(best.rowId());
  }

  @Test void theFortyPercentRateIsAcceptedForBilling() {
    loaded();
    GstService.RowView cola = service.suggest("coca cola", null, null, null, null).best();
    assertThat(cola.hsn()).isEqualTo("220210");
    assertThat(cola.gstPercent()).isEqualTo(new BigDecimal("40"));
    assertThat(cola.acceptedForBilling()).isTrue();
  }

  @Test void priceBandRowsCarryTheirLimitsAndThePriceDecidesTheBand() {
    loaded();
    GstService.RowView low = service.suggest("t shirt", null, new BigDecimal("499"), null, null).best();
    GstService.RowView high = service.suggest("t shirt", null, new BigDecimal("2500.01"), null, null).best();
    assertThat(low.condition()).isEqualTo("PRICE_UPTO_2500");
    assertThat(low.priceMax()).isEqualTo(new BigDecimal("2500"));
    assertThat(low.priceMin()).isNull();
    assertThat(high.condition()).isEqualTo("PRICE_ABOVE_2500");
    assertThat(high.priceMin()).isEqualTo(new BigDecimal("2500"));
  }

  @Test void gujaratiScriptNamesMatchTheGujaratiNameColumn() {
    GstRow.Keyword gu = new GstRow.Keyword(UUID.randomUUID(), 1, "Sample Rice", "ચોખા", "sample rice", "1006", "", "", "", "VERIFIED");
    when(repo.activeKeywords()).thenReturn(GstFixtures.plus(GstCsv.keywords(), gu));
    loaded();
    GstService.Suggestion s = service.suggest("ચોખા", null, null, null, null);
    assertThat(s.best()).isNotNull();
    assertThat(s.best().hsn()).isEqualTo("1006");
    assertThat(s.best().matchedName()).isEqualTo("Sample Rice");
  }

  // --- list, hsn, rates -----------------------------------------------------------------------------------------

  @Test void theListIsExactAndPagedTwentyByDefault() {
    loaded();
    Page<GstService.RowView> p = service.list(null, null, null, 0, 20);
    assertThat(p.total()).isEqualTo(564);
    assertThat(p.totalExact()).isTrue();
    assertThat(p.hasMore()).isTrue();
    assertThat(p.items()).hasSize(20);
    assertThat(p.items().get(0).matchedName()).isNull();
    assertThat(p.items().get(0).score()).isNull();
    Page<GstService.RowView> last = service.list("", null, null, 11, 50);
    assertThat(last.items()).hasSize(14);
    assertThat(last.hasMore()).isFalse();
    assertThat(service.list(null, null, null, 99, 50).items()).isEmpty();
  }

  @Test void theListFiltersByVerificationAndTaxTypeAndSearchesByCodeOrWord() {
    loaded();
    assertThat(service.list(null, "VERIFY_PRODUCT", null, 0, 1).total()).isEqualTo(162);
    assertThat(service.list(null, "VERIFIED", null, 0, 1).total()).isEqualTo(402);
    assertThat(service.list(null, null, "NIL", 0, 1).total()).isEqualTo(103);
    assertThat(service.list(null, " ", " ", 0, 1).total()).isEqualTo(564);
    assertThat(service.list("1006", null, null, 0, 20).items()).allMatch(r -> r.hsn().startsWith("1006"));
    assertThat(service.list("basmati", null, null, 0, 50).items()).extracting(GstService.RowView::hsn).contains("1006");
    assertThat(service.list("basmati", "VERIFY_PRODUCT", null, 0, 50).items()).allMatch(r -> r.verification().equals("VERIFY_PRODUCT"));
  }

  @Test void theListRejectsBadPagingAndFilters() {
    loaded();
    assertThat(fields(thrown(() -> service.list(null, null, null, -1, 20)))).containsKey("page");
    assertThat(fields(thrown(() -> service.list(null, null, null, 0, 0)))).containsKey("size");
    assertThat(fields(thrown(() -> service.list(null, null, null, 0, 51)))).containsKey("size");
    assertThat(fields(thrown(() -> service.list(null, "MAYBE", null, 0, 20)))).containsKey("verification");
    assertThat(fields(thrown(() -> service.list(null, null, "ZERO", 0, 20)))).containsKey("taxType");
  }

  @Test void oneHsnShowsAllItsConditionsAndUnknownOrMalformedCodesFail() {
    loaded();
    GstService.HsnRows rice = service.byHsn(" 1006 ");
    assertThat(rice.hsn()).isEqualTo("1006");
    assertThat(rice.items()).extracting(GstService.RowView::condition).containsExactlyInAnyOrder("LOOSE", "PACKED_LABELLED");
    assertThat(service.byHsn("0401").items()).hasSize(3).allMatch(r -> r.gstPercent().signum() == 0);
    ApiException unknown = thrown(() -> service.byHsn("9999"));
    assertThat(unknown.code()).isEqualTo("NOT_FOUND");
    assertThat(fields(thrown(() -> service.byHsn("12")))).containsKey("hsn");
    assertThat(fields(thrown(() -> service.byHsn(null)))).containsKey("hsn");
  }

  @Test void ratesPrintAsPlainNumbersWithTheBillingFlag() {
    loaded();
    List<GstService.RateView> rates = service.rates().items();
    assertThat(rates).extracting(GstService.RateView::gstPercent)
        .containsExactly(new BigDecimal("0"), new BigDecimal("0.25"), new BigDecimal("1.5"), new BigDecimal("5"), new BigDecimal("40"));
    assertThat(rates.get(3).cgstRate()).isEqualTo(new BigDecimal("2.5"));
    assertThat(rates).extracting(GstService.RateView::acceptedForBilling).containsExactly(true, true, true, true, true);
  }

  // --- defaults -------------------------------------------------------------------------------------------------

  @Test void defaultsFillFromAConfidentMatch() {
    loaded();
    GstService.Defaults d = service.defaultsFor("  Basmati Rice 5 kg ", "Staples", null).orElseThrow();
    assertThat(d.hsn()).isEqualTo("1006");
    assertThat(d.gstPercent()).isEqualTo(new BigDecimal("5"));
    assertThat(d.score()).isGreaterThanOrEqualTo(GstService.FILL_MIN_SCORE);
    assertThat(d.rowId()).isNotNull();
    assertThat(d.verification()).isIn("VERIFIED", "VERIFY_PRODUCT");
  }

  @Test void defaultsCarryTheFortyPercentRate() {
    loaded();
    GstService.Defaults d = service.defaultsFor("coca cola", null, null).orElseThrow();
    assertThat(d.hsn()).isEqualTo("220210");
    assertThat(d.gstPercent()).isEqualByComparingTo("40");
  }

  @Test void defaultsLeaveTheRateOutWhenBillingCannotStoreIt() {
    when(repo.allHsn()).thenReturn(List.of(GstFixtures.hsn("3333", 1, "ANY", "7", "VERIFIED")));
    when(repo.activeKeywords()).thenReturn(List.of(GstFixtures.keyword(1, "Zorbo Widget", "zorbo widget", "3333", "VERIFIED")));
    loaded();
    GstService.Defaults d = service.defaultsFor("zorbo widget", null, null).orElseThrow();
    assertThat(d.hsn()).isEqualTo("3333");
    assertThat(d.gstPercent()).isNull();
  }

  @Test void defaultsAreEmptyForBlankNamesJunkAndWeakMatches() {
    loaded();
    assertThat(service.defaultsFor(null, null, null)).isEmpty();
    assertThat(service.defaultsFor("   ", null, null)).isEmpty();
    assertThat(service.defaultsFor("xyzzy", null, null)).isEmpty();
    GstService.Suggestion weak = service.suggest("o'neil", null, null, null, null);
    assertThat(weak.best()).isNotNull();
    assertThat(weak.best().score()).isLessThan(GstService.FILL_MIN_SCORE);
    assertThat(service.defaultsFor("o'neil", null, null)).isEmpty();
  }

  @Test void aVerifiedRowWinsATieAgainstAnUnverifiedBest() {
    when(repo.allHsn()).thenReturn(List.of(GstFixtures.hsn("1111", 1, "ANY", "5", "VERIFY_PRODUCT"), GstFixtures.hsn("2222", 2, "ANY", "12", "VERIFIED")));
    when(repo.activeKeywords()).thenReturn(List.of(GstFixtures.keyword(1, "Zorbo Widget", "zorbo widget", "1111", "VERIFIED"),
        GstFixtures.keyword(2, "Zorbo Widget", "zorbo widget", "2222", "VERIFIED")));
    loaded();
    GstService.Suggestion s = service.suggest("zorbo widget", null, null, null, 3);
    assertThat(s.best().hsn()).isEqualTo("1111");
    assertThat(s.best().verification()).isEqualTo("VERIFY_PRODUCT");
    GstService.Defaults d = service.defaultsFor("zorbo widget", null, null).orElseThrow();
    assertThat(d.hsn()).isEqualTo("2222");
    assertThat(d.verification()).isEqualTo("VERIFIED");
    assertThat(d.gstPercent()).isEqualTo(new BigDecimal("12"));
  }

  @Test void aVerifiedAlternativeThatIsClearlyWeakerDoesNotWin() {
    when(repo.allHsn()).thenReturn(List.of(GstFixtures.hsn("1111", 1, "ANY", "5", "VERIFY_PRODUCT"), GstFixtures.hsn("2222", 2, "ANY", "12", "VERIFIED")));
    when(repo.activeKeywords()).thenReturn(List.of(GstFixtures.keyword(1, "Zorbo Widget Deluxe", "zorbo widget deluxe", "1111", "VERIFY_PRODUCT"),
        GstFixtures.keyword(2, "Zorbo Gadget", "zorbo gadget", "2222", "VERIFIED")));
    loaded();
    GstService.Defaults d = service.defaultsFor("zorbo widget deluxe", null, null).orElseThrow();
    assertThat(d.hsn()).isEqualTo("1111");
  }

  // --- design: reads never touch the database; speed ----------------------------------------------------------

  @Test void suggestListHsnRatesAndDefaultsMakeNoRepositoryCall() {
    loaded();
    clearInvocations(repo);
    for (String n : List.of("basmati rice", "chakki atta", "t shirt", "xyzzy", "shampoo")) {
      service.suggest(n, null, null, null, null);
      service.defaultsFor(n, null, null);
    }
    service.list("rice", null, null, 0, 20);
    service.byHsn("1006");
    service.rates();
    verifyNoInteractions(repo);
  }

  @Test void thousandSuggestionsAnswerWellUnderFiftyMillisecondsAtTheNinetyFifthPercentile() {
    loaded();
    List<String> names = new ArrayList<>();
    for (GstRow.Keyword k : GstCsv.keywords()) if (k.rowNo() % 6 == 0) names.add(k.name());
    long[] nanos = new long[1000];
    for (int i = 0; i < nanos.length; i++) {
      String n = names.get(i % names.size());
      long t = System.nanoTime();
      service.suggest(n, null, null, null, null);
      nanos[i] = System.nanoTime() - t;
    }
    Arrays.sort(nanos);
    double p95Ms = nanos[(int) (nanos.length * 0.95)] / 1_000_000.0;
    System.out.printf("GST suggest p95 over 1000 queries: %.3f ms (median %.3f ms)%n", p95Ms, nanos[500] / 1_000_000.0);
    assertThat(p95Ms).isLessThan(50.0);
  }
}
