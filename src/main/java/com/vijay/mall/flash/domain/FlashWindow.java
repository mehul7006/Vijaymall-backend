package com.vijay.mall.flash.domain;

import java.time.Duration;
import java.time.Instant;

/** Rules for a sale's time window. */
public final class FlashWindow {
  private FlashWindow() {}

  public static final Duration MAX_LENGTH = Duration.ofDays(30);
  /** A start a little in the past is "now" (clock differences between the app and the server). */
  public static final Duration PAST_GRACE = Duration.ofMinutes(1);

  /** Two windows overlap when each starts before the other ends (touching ends do not overlap). */
  public static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
    return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
  }

  /** Returns the problem with a window, or null. {@code now} is the server clock. */
  public static String problem(Instant start, Instant end, Instant now, boolean startMayBePast) {
    if (end == null) return "endsAt is required";
    if (!end.isAfter(start)) return "endsAt must be after startsAt";
    if (!end.isAfter(now)) return "endsAt must be in the future";
    if (!startMayBePast && start.isBefore(now.minus(PAST_GRACE))) return "startsAt must not be in the past";
    if (Duration.between(start, end).compareTo(MAX_LENGTH) > 0) return "a sale can run for at most 30 days";
    return null;
  }
}
