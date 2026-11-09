package com.vijay.mall.common.web;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The one place that reads an instant a client sends, in a JSON body or in a query parameter: a proper ISO-8601 instant with
 * {@code Z} or an offset, a zone-less date-time ({@code 2026-11-09T10:00:00}, India time) or a plain date ({@code 2026-11-09},
 * the start of that day in India).
 */
public final class RequestInstants {
  /** The shop's time zone, used when the client sends no zone. */
  static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private RequestInstants() {}

  /**
   * The instant for a zone-less date-time or a plain date read as India time, or {@code null} when the text is anything else
   * (a caller then falls back to the standard ISO reader, which accepts or rejects it).
   */
  static Instant parseIndia(String text) {
    String s = text.trim();
    try {
      return LocalDate.parse(s).atStartOfDay(INDIA).toInstant();
    } catch (DateTimeException notADate) {
      try {
        return LocalDateTime.parse(s).atZone(INDIA).toInstant();
      } catch (DateTimeException notADateTime) {
        return null;
      }
    }
  }

  /**
   * Reads any of the accepted forms.
   * @throws DateTimeException when the text is none of them
   */
  public static Instant parse(String text) {
    Instant india = parseIndia(text);
    return india != null ? india : Instant.parse(text.trim());
  }
}
