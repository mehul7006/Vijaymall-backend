package com.vijay.mall.flash.domain;

import java.time.Instant;

/** Worked out from the clock and cancelled_at; never stored. */
public enum FlashStatus {
  SCHEDULED, ACTIVE, ENDED, CANCELLED;

  /**
   * Works out where a sale is in its life.
   * @param startsAt when the sale begins (inclusive)
   * @param endsAt when the sale ends (exclusive)
   * @param cancelledAt when staff cancelled it, or null
   * @param now the current instant
   * @return {@code CANCELLED} when cancelled, else {@code SCHEDULED} before the start, {@code ACTIVE} until the end, then {@code ENDED}
   */
  public static FlashStatus of(Instant startsAt, Instant endsAt, Instant cancelledAt, Instant now) {
    if (cancelledAt != null) return CANCELLED;
    if (now.isBefore(startsAt)) return SCHEDULED;
    if (now.isBefore(endsAt)) return ACTIVE;
    return ENDED;
  }
}
