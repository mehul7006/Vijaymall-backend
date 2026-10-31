package com.vijay.mall.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
  static class MutableClock extends Clock {
    long millis = 1_000_000;
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId z) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    @Override public long millis() { return millis; }
  }

  @Test
  void blocksAfterMaxCallsInWindowAndRecovers() {
    MutableClock clock = new MutableClock();
    RateLimiter rl = new RateLimiter(clock);
    for (int i = 0; i < 3; i++) rl.check("k", 3, 60);
    ApiException e = assertThrows(ApiException.class, () -> rl.check("k", 3, 60));
    assertEquals("RATE_LIMITED", e.code());
    assertEquals(429, e.status().value());
    clock.millis += 61_000;
    assertDoesNotThrow(() -> rl.check("k", 3, 60));
  }

  @Test
  void keysAreIndependent() {
    RateLimiter rl = new RateLimiter(new MutableClock());
    rl.check("a", 1, 60);
    assertDoesNotThrow(() -> rl.check("b", 1, 60));
  }

  @Test
  void reportsRetryAfter() {
    MutableClock clock = new MutableClock();
    RateLimiter rl = new RateLimiter(clock);
    rl.check("k", 1, 60);
    clock.millis += 10_000;
    ApiException e = assertThrows(ApiException.class, () -> rl.check("k", 1, 60));
    assertEquals(50L, e.details().get("retryAfterSeconds"));
  }

  @Test
  void cleanupForgetsIdleKeysButKeepsRecentOnes() {
    MutableClock clock = new MutableClock();
    RateLimiter rl = new RateLimiter(clock);
    rl.check("old", 1, 60);
    clock.millis += 3_600_001L;   // more than an hour later
    rl.check("recent", 1, 60);
    rl.cleanup();
    assertDoesNotThrow(() -> rl.check("old", 1, 60));              // forgotten, so allowed again
    assertThrows(ApiException.class, () -> rl.check("recent", 1, 60)); // still remembered
  }
}
