package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.identity.domain.OtpHasher;
import com.vijay.mall.identity.infra.OtpRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class MobileOtpServiceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
  private static final String PEPPER = "pepper";
  private static final String MOBILE = "9876543210";

  private final OtpRepository repo = mock(OtpRepository.class);
  private final SmsOtpSender sender = mock(SmsOtpSender.class);
  private final RateLimiter limiter = mock(RateLimiter.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private MobileOtpService service(String fixedCode) {
    var props = new AppProps(null, new AppProps.Otp("console", PEPPER, fixedCode, "from@x"), null, null, null, null);
    return new MobileOtpService(repo, sender, props, clock, limiter, 20);
  }

  private static String hash(String code) { return OtpHasher.otpHash(PEPPER, "mobile:" + MOBILE, code); }

  @Test
  void sendNormalisesTheNumberStoresAHashAndTextsTheCode() {
    service("12345").send("+91 98765 43210", "1.2.3.4");
    verify(limiter).check("otp-mobile:" + MOBILE, 5, 3600);
    verify(limiter).check("otp-ip:1.2.3.4", 20, 3600);
    verify(repo).insertMobile(MOBILE, hash("12345"), NOW.plus(Duration.ofMinutes(5)), NOW);
    verify(sender).send(MOBILE, "12345");
  }

  @Test
  void sendGeneratesFiveDigitCodeWhenNoFixedCode() {
    service("").send(MOBILE, "ip");
    var code = ArgumentCaptor.forClass(String.class);
    verify(sender).send(eq(MOBILE), code.capture());
    assertThat(code.getValue()).matches("\\d{5}");
    verify(repo).insertMobile(MOBILE, hash(code.getValue()), NOW.plus(Duration.ofMinutes(5)), NOW);
  }

  @Test
  void badNumberIsAValidationError() {
    assertThatThrownBy(() -> service("1").send("12345", "ip")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    verify(sender, never()).send(any(), any());
  }

  @Test
  void sendStopsWhenRateLimited() {
    doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "slow down"))
        .when(limiter).check(eq("otp-mobile:" + MOBILE), anyInt(), anyLong());
    assertThatThrownBy(() -> service("1").send(MOBILE, "ip")).isInstanceOf(ApiException.class);
    verify(sender, never()).send(any(), any());
    verify(repo, never()).insertMobile(any(), any(), any(), any());
  }

  @Test
  void verifyConsumesChallengeOnRightCode() {
    UUID id = UUID.randomUUID();
    when(repo.latestOpenMobile(MOBILE)).thenReturn(Optional.of(
        new OtpRepository.Challenge(id, hash("12345"), NOW.plusSeconds(60), 0)));
    service("x").verify("+919876543210", " 12345 ");
    verify(repo).consume(id);
  }

  @Test
  void aCodeSentToAnEmailAddressNeverMatchesAMobileChallenge() {
    UUID id = UUID.randomUUID();
    String emailStyleHash = OtpHasher.otpHash(PEPPER, MOBILE, "12345");
    when(repo.latestOpenMobile(MOBILE)).thenReturn(Optional.of(
        new OtpRepository.Challenge(id, emailStyleHash, NOW.plusSeconds(60), 0)));
    assertThatThrownBy(() -> service("x").verify(MOBILE, "12345")).isInstanceOf(ApiException.class);
    verify(repo, never()).consume(any());
  }

  @Test
  void wrongCodeCountsAnAttempt() {
    UUID id = UUID.randomUUID();
    when(repo.latestOpenMobile(MOBILE)).thenReturn(Optional.of(
        new OtpRepository.Challenge(id, hash("12345"), NOW.plusSeconds(60), 2)));
    assertThatThrownBy(() -> service("x").verify(MOBILE, "00000")).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("OTP_INVALID");
      assertThat(e.details()).containsEntry("reason", "WRONG");
    });
    verify(repo).addAttempt(id);
  }

  @Test
  void nullCodeIsJustWrong() {
    when(repo.latestOpenMobile(MOBILE)).thenReturn(Optional.of(
        new OtpRepository.Challenge(UUID.randomUUID(), hash("12345"), NOW.plusSeconds(60), 0)));
    assertThatThrownBy(() -> service("x").verify(MOBILE, null)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details()).containsEntry("reason", "WRONG"));
  }

  @Test
  void noChallengeOrAnOldOneMeansExpired() {
    when(repo.latestOpenMobile(MOBILE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service("x").verify(MOBILE, "12345")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details()).containsEntry("reason", "EXPIRED"));
    when(repo.latestOpenMobile(MOBILE)).thenReturn(Optional.of(
        new OtpRepository.Challenge(UUID.randomUUID(), hash("12345"), NOW.minusSeconds(1), 0)));
    assertThatThrownBy(() -> service("x").verify(MOBILE, "12345")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details()).containsEntry("reason", "EXPIRED"));
  }

  @Test
  void lockedAfterFiveWrongTries() {
    when(repo.latestOpenMobile(MOBILE)).thenReturn(Optional.of(
        new OtpRepository.Challenge(UUID.randomUUID(), hash("12345"), NOW.plusSeconds(60), 5)));
    assertThatThrownBy(() -> service("x").verify(MOBILE, "12345")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.details()).containsEntry("reason", "LOCKED"));
    verify(repo, never()).consume(any());
  }

  @Test
  void maskHidesTheMiddleDigits() {
    assertThat(MobileOtpService.mask(MOBILE)).isEqualTo("98******10");
  }
}
