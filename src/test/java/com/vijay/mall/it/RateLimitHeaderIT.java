package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.ratelimit.RateLimitFilter;
import com.vijay.mall.identity.app.OtpService;
import com.vijay.mall.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/** A real 429 from the code limit and from the per-address API limit carries the Retry-After header. */
@SuppressWarnings("rawtypes")
class RateLimitHeaderIT extends IntegrationTestBase {
  @Autowired OtpService otpService;
  @Autowired RateLimitFilter rateLimitFilter;

  /** The shared test context raises every limit; this test lowers two of them for its own duration. */
  @AfterEach
  void restoreLimits() {
    ReflectionTestUtils.setField(otpService, "perEmailHour", 100000);
    ReflectionTestUtils.setField(rateLimitFilter, "perMinute", 100000);
  }

  @Test
  void bothLimitsSendRetryAfter() {
    ReflectionTestUtils.setField(otpService, "perEmailHour", 1);
    String email = freshEmail();
    assertThat(post("/api/v1/auth/otp/send", null, Map.of("email", email)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    var second = post("/api/v1/auth/otp/send", null, Map.of("email", email));
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(second.getBody()).containsEntry("code", "RATE_LIMITED");
    assertThat(second.getBody().get("message").toString()).startsWith("Too many codes requested. Try again in ").endsWith(" seconds.");
    long wait = ((Number) ((Map) second.getBody().get("details")).get("retryAfterSeconds")).longValue();
    assertThat(second.getHeaders().getFirst("Retry-After")).isEqualTo(String.valueOf(wait));

    // earlier calls from this address are still inside the minute window, so a limit of one refuses the next call
    ReflectionTestUtils.setField(rateLimitFilter, "perMinute", 1);
    ResponseEntity<Map> last = get("/api/v1/categories", null);
    assertThat(last.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(Long.parseLong(last.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
  }
}
