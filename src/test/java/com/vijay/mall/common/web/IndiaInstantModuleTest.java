package com.vijay.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Instant request fields: ISO with a zone as before, zone-less and date-only read as India time. */
class IndiaInstantModuleTest {
  record Body(Instant at) {}

  private final JsonMapper mapper = JsonMapper.builder().addModule(new IndiaInstantModule().lenientInstantModule()).build();

  private Instant read(String json) { return mapper.readValue("{\"at\":" + json + "}", Body.class).at(); }

  @Test
  void properIsoWithZoneOrOffsetIsUnchanged() {
    assertThat(read("\"2026-11-09T10:00:00Z\"")).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
    assertThat(read("\"2026-11-09T10:00:00+05:30\"")).isEqualTo(Instant.parse("2026-11-09T04:30:00Z"));
  }

  @Test
  void zoneLessDateTimeIsIndiaTime() {
    assertThat(read("\"2026-11-09T10:00:00\"")).isEqualTo(Instant.parse("2026-11-09T04:30:00Z"));
    assertThat(read("\" 2026-11-09T10:00 \"")).isEqualTo(Instant.parse("2026-11-09T04:30:00Z"));
  }

  @Test
  void plainDateIsTheStartOfThatDayInIndia() {
    assertThat(read("\"2026-11-09\"")).isEqualTo(Instant.parse("2026-11-08T18:30:00Z"));
  }

  @Test
  void nullAndNumbersKeepTheirUsualMeaning() {
    assertThat(read("null")).isNull();
    assertThat(read("1700000000")).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"garbage\"", "\"2026-13-45\"", "\"2026-11-09T25:00:00\"" })
  void garbageIsStillRejected(String json) {
    assertThatThrownBy(() -> read(json)).isInstanceOf(JacksonException.class);
  }

  @Test
  void writingIsUnchangedUtcWithZ() {
    assertThat(mapper.writeValueAsString(new Body(Instant.parse("2026-11-09T04:30:00Z")))).isEqualTo("{\"at\":\"2026-11-09T04:30:00Z\"}");
  }
}
