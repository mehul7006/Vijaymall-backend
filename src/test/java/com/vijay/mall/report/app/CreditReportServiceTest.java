package com.vijay.mall.report.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CreditReportServiceTest {
  private static final Instant AT = Instant.parse("2027-03-01T10:00:00Z");

  @Test
  void timestampsFromNativeQueriesAreReadWhateverTypeTheDriverReturns() {
    assertThat(CreditReportService.toInstant(null)).isNull();
    assertThat(CreditReportService.toInstant(AT)).isEqualTo(AT);
    assertThat(CreditReportService.toInstant(OffsetDateTime.ofInstant(AT, ZoneOffset.ofHoursMinutes(5, 30)))).isEqualTo(AT);
    assertThat(CreditReportService.toInstant(Timestamp.from(AT))).isEqualTo(AT);
    assertThat(CreditReportService.toInstant("2027-03-01T10:00:00Z")).isEqualTo(AT);
  }
}
