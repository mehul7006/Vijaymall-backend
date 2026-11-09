package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.identity.infra.OtpRepository;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The codes stay the same, the messages say what went wrong. */
class OtpErrorsTest {
  @Test
  void eachReasonHasItsOwnMessageAndKeepsTheCode() {
    assertThat(OtpErrors.invalid("WRONG").getMessage()).isEqualTo("That code is not right. Check it and try again.");
    assertThat(OtpErrors.invalid("EXPIRED").getMessage()).isEqualTo("That code has expired. Request a new one.");
    assertThat(OtpErrors.invalid("LOCKED").getMessage()).isEqualTo("Too many wrong attempts. Request a new code.");
    var e = OtpErrors.invalid("WRONG");
    assertThat(e.code()).isEqualTo("OTP_INVALID");
    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(e.details()).containsEntry("reason", "WRONG");
  }

  @Test
  void rateLimitMessageNamesTheWaitAndKeepsTheDetails() {
    var limited = new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests", Map.of("retryAfterSeconds", 90L));
    var e = OtpErrors.tooManyCodes(limited);
    assertThat(e.getMessage()).isEqualTo("Too many codes requested. Try again in 90 seconds.");
    assertThat(e.code()).isEqualTo("RATE_LIMITED");
    assertThat(e.retryAfterSeconds()).isEqualTo(90L);
    var noWait = OtpErrors.tooManyCodes(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "x"));
    assertThat(noWait.getMessage()).isEqualTo("Too many codes requested. Try again later.");
  }

  @Test
  void bothSendersTurnALimitIntoTheOtpMessage() {
    var limiter = mock(RateLimiter.class);
    var props = new AppProps(null, new AppProps.Otp("console", "pepper", "12345", "f@x"), null, null, null, null);
    var limited = new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests", Map.of("retryAfterSeconds", 7L));
    doThrow(limited).when(limiter).check(eq("otp-ip:ip"), anyInt(), anyLong());
    var mail = new OtpService(mock(OtpRepository.class), mock(OtpSender.class), props, Clock.systemUTC(), limiter, 20);
    var sms = new MobileOtpService(mock(OtpRepository.class), mock(SmsOtpSender.class), props, Clock.systemUTC(), limiter, 20);
    assertThatThrownBy(() -> mail.send("a@b.com", "ip")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.getMessage()).isEqualTo("Too many codes requested. Try again in 7 seconds."));
    assertThatThrownBy(() -> sms.send("9876543210", "ip")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.getMessage()).isEqualTo("Too many codes requested. Try again in 7 seconds."));
  }
}
