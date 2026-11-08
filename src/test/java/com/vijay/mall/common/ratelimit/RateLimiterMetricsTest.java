package com.vijay.mall.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterMetricsTest {
  @Test
  void refusedCallsAreCountedByKindOfLimitNeverByPerson() {
    var registry = new SimpleMeterRegistry();
    var limiter = new RateLimiter(Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC));
    limiter.setMetrics(registry);
    limiter.check("otp-email:asha@example.com", 1, 60);
    assertThatThrownBy(() -> limiter.check("otp-email:asha@example.com", 1, 60)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> limiter.check("plainkey", 0, 60)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details()).containsEntry("retryAfterSeconds", 60L)); // a limit of 0 used to crash with a null pointer
    assertThat(registry.get("mall.rate.limited").tag("scope", "otp-email").counter().count()).isEqualTo(1);
    assertThat(registry.get("mall.rate.limited").tag("scope", "plainkey").counter().count()).isEqualTo(1);
    assertThat(registry.getMeters()).noneMatch(m -> m.getId().toString().contains("asha"));
  }
}
