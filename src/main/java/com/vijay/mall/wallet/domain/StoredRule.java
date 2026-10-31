package com.vijay.mall.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/** A coin rule as saved by the admin: the pure rule plus id, active window and flag. */
public record StoredRule(UUID id, CoinRule rule, Instant startAt, Instant endAt, boolean active) {
  public boolean isLive(Instant now) {
    return active && (startAt == null || !now.isBefore(startAt)) && (endAt == null || !now.isAfter(endAt));
  }
}
