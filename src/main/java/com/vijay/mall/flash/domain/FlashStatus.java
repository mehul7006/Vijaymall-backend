package com.vijay.mall.flash.domain;

import java.time.Instant;

/** Worked out from the clock and cancelled_at; never stored. */
public enum FlashStatus {
  SCHEDULED, ACTIVE, ENDED, CANCELLED;

  public static FlashStatus of(Instant startsAt, Instant endsAt, Instant cancelledAt, Instant now) {
    if (cancelledAt != null) return CANCELLED;
    if (now.isBefore(startsAt)) return SCHEDULED;
    if (now.isBefore(endsAt)) return ACTIVE;
    return ENDED;
  }
}
