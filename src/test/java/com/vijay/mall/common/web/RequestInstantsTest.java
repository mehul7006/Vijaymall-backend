package com.vijay.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.AppProps;
import java.time.DateTimeException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.format.support.DefaultFormattingConversionService;

/** The shared instant reader and its use for query parameters (for example the POS {@code updatedSince}). */
class RequestInstantsTest {
  @Test
  void readsEveryAcceptedForm() {
    assertThat(RequestInstants.parse("2026-11-09T10:00:00Z")).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
    assertThat(RequestInstants.parse("2026-11-09T10:00:00+05:30")).isEqualTo(Instant.parse("2026-11-09T04:30:00Z"));
    assertThat(RequestInstants.parse("2026-11-09T10:00:00")).isEqualTo(Instant.parse("2026-11-09T04:30:00Z"));
    assertThat(RequestInstants.parse(" 2026-11-09 ")).isEqualTo(Instant.parse("2026-11-08T18:30:00Z"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"garbage", "2026-13-45", "2026-11-09T25:00:00"})
  void rejectsAnythingElse(String text) {
    assertThatThrownBy(() -> RequestInstants.parse(text)).isInstanceOf(DateTimeException.class);
  }

  @Test
  void queryParametersUseTheSameReader() {
    DefaultFormattingConversionService conversion = new DefaultFormattingConversionService();
    new WebConfig(new AppProps(null, null, null, null, null, null)).addFormatters(conversion);
    assertThat(conversion.convert("2026-11-09", Instant.class)).isEqualTo(Instant.parse("2026-11-08T18:30:00Z"));
    assertThat(conversion.convert("2026-11-09T10:00:00", Instant.class)).isEqualTo(Instant.parse("2026-11-09T04:30:00Z"));
    assertThat(conversion.convert("2026-11-09T10:00:00Z", Instant.class)).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
    assertThatThrownBy(() -> conversion.convert("nope", Instant.class)).isInstanceOf(ConversionFailedException.class);
  }

  @Test
  void theEndOfARangeCoversTheWholeDayForAPlainDateAndIsTheInstantItselfOtherwise() {
    assertThat(RequestInstants.parseEnd(" 2026-11-09 ")).isEqualTo(Instant.parse("2026-11-09T18:30:00Z"));
    assertThat(RequestInstants.parseEnd("2026-11-09T10:00:00")).isEqualTo(Instant.parse("2026-11-09T04:30:00Z"));
    assertThat(RequestInstants.parseEnd("2026-11-09T10:00:00Z")).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
    assertThatThrownBy(() -> RequestInstants.parseEnd("nope")).isInstanceOf(DateTimeException.class);
  }
}
