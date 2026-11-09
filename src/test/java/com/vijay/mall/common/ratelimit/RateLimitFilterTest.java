package com.vijay.mall.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {
  private final RateLimiter limiter = new RateLimiter(Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));

  private RateLimitFilter filter(int perMinute) { return filter(perMinute, ""); }
  private RateLimitFilter filter(int perMinute, String ipHeader) {
    return new RateLimitFilter(limiter, JsonMapper.builder().build(), perMinute, ipHeader);
  }

  private static MockHttpServletRequest req(String uri) {
    var r = new MockHttpServletRequest("GET", uri);
    r.setRemoteAddr("5.5.5.5");
    return r;
  }

  @Test
  void callsUnderTheLimitPassThrough() throws Exception {
    var calls = new AtomicInteger();
    var f = filter(2);
    for (int i = 0; i < 2; i++) f.doFilter(req("/api/v1/x"), new MockHttpServletResponse(), (a, b) -> calls.incrementAndGet());
    assertThat(calls).hasValue(2);
  }

  @Test
  void overTheLimitGets429WithJsonErrorAndChainIsNotCalled() throws Exception {
    var calls = new AtomicInteger();
    var f = filter(1);
    f.doFilter(req("/api/v1/x"), new MockHttpServletResponse(), (a, b) -> calls.incrementAndGet());
    var res = new MockHttpServletResponse();
    f.doFilter(req("/api/v1/x"), res, (a, b) -> calls.incrementAndGet());
    assertThat(calls).hasValue(1);
    assertThat(res.getStatus()).isEqualTo(429);
    assertThat(res.getContentType()).startsWith("application/json");
    assertThat(res.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"").contains("retryAfterSeconds");
  }

  @Test
  void limitIsPerClientAddress() throws Exception {
    var f = filter(1);
    f.doFilter(req("/api/v1/x"), new MockHttpServletResponse(), (a, b) -> { });
    var other = req("/api/v1/x");
    other.setRemoteAddr("6.6.6.6");
    var res = new MockHttpServletResponse();
    f.doFilter(other, res, (a, b) -> { });
    assertThat(res.getStatus()).isEqualTo(200);
  }

  @ParameterizedTest
  @CsvSource({"/actuator/health", "/img/a.jpg", "/swagger-ui/index.html"})
  void nonApiPathsAreNeverLimited(String path) throws Exception {
    var f = filter(0);
    var calls = new AtomicInteger();
    f.doFilter(req(path), new MockHttpServletResponse(), (a, b) -> calls.incrementAndGet());
    assertThat(calls).hasValue(1);
  }

  @Test
  void forwardedHeadersAreIgnoredUnlessOneIsTrusted() {
    filter(10, "");
    var r = req("/api/x");
    r.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
    r.addHeader("CF-Connecting-IP", "3.3.3.3");
    assertThat(RateLimitFilter.clientIp(r)).isEqualTo("5.5.5.5");
  }

  @Test
  void theTrustedHeaderAloneIsBelievedAndFallsBackToTheSocket() {
    filter(10, "CF-Connecting-IP");
    var r = req("/api/x");
    r.addHeader("X-Forwarded-For", "1.1.1.1");
    assertThat(RateLimitFilter.clientIp(r)).isEqualTo("5.5.5.5");
    r.addHeader("CF-Connecting-IP", " 3.3.3.3, 4.4.4.4 ");
    assertThat(RateLimitFilter.clientIp(r)).isEqualTo("3.3.3.3");
    var blank = req("/api/x");
    blank.addHeader("CF-Connecting-IP", "  ");
    assertThat(RateLimitFilter.clientIp(blank)).isEqualTo("5.5.5.5");
    filter(10, ""); // leave the static setting clean for other tests
  }
}
