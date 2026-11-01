package com.vijay.mall.report.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReportSummaryRangeTest {
  private static final LocalDate TODAY = LocalDate.parse("2027-03-10");

  @Test
  void defaultIsTheLastSevenDaysIncludingToday() {
    var r = ReportSummaryService.range(null, null, null, TODAY);
    assertThat(r.from()).isEqualTo(LocalDate.parse("2027-03-04"));
    assertThat(r.to()).isEqualTo(TODAY);
  }

  @Test
  void monthAndQuarterAre30And90Days() {
    assertThat(ReportSummaryService.range("MONTH", null, null, TODAY).from()).isEqualTo(TODAY.minusDays(29));
    assertThat(ReportSummaryService.range(" quarter ", null, null, TODAY).from()).isEqualTo(TODAY.minusDays(89));
    assertThat(ReportSummaryService.range("", null, null, TODAY).from()).isEqualTo(TODAY.minusDays(6));
  }

  @Test
  void explicitDatesWinOverPeriod() {
    var r = ReportSummaryService.range("quarter", LocalDate.parse("2027-01-01"), LocalDate.parse("2027-01-31"), TODAY);
    assertThat(r.from()).isEqualTo(LocalDate.parse("2027-01-01"));
    assertThat(r.to()).isEqualTo(LocalDate.parse("2027-01-31"));
  }

  @Test
  void fromWithoutToEndsToday() {
    assertThat(ReportSummaryService.range(null, TODAY.minusDays(2), null, TODAY).to()).isEqualTo(TODAY);
  }

  @Test
  void unknownPeriodEndBeforeStartAndHugeRangesAreRejected() {
    assertThatThrownBy(() -> ReportSummaryService.range("year", null, null, TODAY)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ReportSummaryService.range(null, TODAY, TODAY.minusDays(1), TODAY))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ReportSummaryService.range(null, TODAY.minusDays(400), TODAY, TODAY))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void aFullYearIsAllowedButNotOneDayMore() {
    assertThat(ReportSummaryService.range(null, TODAY.minusDays(365), TODAY, TODAY).from()).isEqualTo(TODAY.minusDays(365));
    assertThatThrownBy(() -> ReportSummaryService.range(null, TODAY.minusDays(366), TODAY, TODAY))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void databaseDatesComeBackAsLocalDate() {
    assertThat(ReportSummaryService.toDate(TODAY)).isEqualTo(TODAY);
    assertThat(ReportSummaryService.toDate(java.sql.Date.valueOf(TODAY))).isEqualTo(TODAY);
    assertThat(ReportSummaryService.toDate(new java.util.Date(java.sql.Date.valueOf(TODAY).getTime()))).isEqualTo(TODAY);
    assertThat(ReportSummaryService.toDate("2027-03-10")).isEqualTo(TODAY);
  }
}
