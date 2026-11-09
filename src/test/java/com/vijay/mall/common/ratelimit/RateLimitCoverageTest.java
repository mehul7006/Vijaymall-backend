package com.vijay.mall.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class RateLimitCoverageTest {
  /** A clock the test moves by hand. */
  private static final class Manual extends Clock {
    final AtomicLong millis = new AtomicLong(10_000_000L);
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId z) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    @Override public long millis() { return millis.get(); }
  }

  @AfterEach
  void restoreDefaultHeader() {
    // the trusted header is static; the application default is "none"
    new RateLimitFilter(new RateLimiter(new Manual()), JsonMapper.builder().build(), 10, "");
  }

  @Test
  void cleanupDropsIdleKeysButKeepsRecentOnes() {
    var clock = new Manual();
    var limiter = new RateLimiter(clock);
    limiter.check("old", 1, 60);
    clock.millis.addAndGet(3_700_000L); // more than an hour later
    limiter.check("fresh", 1, 60);

    limiter.cleanup();

    // the idle key was dropped, so it may be used again at once; the fresh one is still counted
    limiter.check("old", 1, 60);
    assertThatThrownBy(() -> limiter.check("fresh", 1, 60)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("RATE_LIMITED"));
  }

  @Test
  void aNullIpHeaderSettingMeansTheSocketAddressIsUsed() {
    new RateLimitFilter(new RateLimiter(new Manual()), JsonMapper.builder().build(), 10, null);
    var r = new MockHttpServletRequest("GET", "/api/v1/x");
    r.setRemoteAddr("7.7.7.7");
    r.addHeader("X-Forwarded-For", "1.1.1.1");
    assertThat(RateLimitFilter.clientIp(r)).isEqualTo("7.7.7.7");
  }

  @Test
  void aConfiguredHeaderIsTrimmedAndItsFirstAddressUsed() {
    new RateLimitFilter(new RateLimiter(new Manual()), JsonMapper.builder().build(), 10, "  X-Real-Ip ");
    var r = new MockHttpServletRequest("GET", "/api/v1/x");
    r.setRemoteAddr("7.7.7.7");
    r.addHeader("X-Real-Ip", "1.1.1.1, 2.2.2.2");
    assertThat(RateLimitFilter.clientIp(r)).isEqualTo("1.1.1.1");
  }
}
