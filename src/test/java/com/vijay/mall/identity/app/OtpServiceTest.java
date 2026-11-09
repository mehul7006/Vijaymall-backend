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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class OtpServiceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
  private static final String PEPPER = "pepper";

  private final OtpRepository repo = mock(OtpRepository.class);
  private final OtpSender sender = mock(OtpSender.class);
  private final RateLimiter limiter = mock(RateLimiter.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private OtpService service(String fixedCode) {
    var props = new AppProps(null, new AppProps.Otp("console", PEPPER, fixedCode, "from@x"), null, null, null, null);
    return new OtpService(repo, sender, props, clock, limiter, 20);
  }

  private static String hash(String email, String code) { return OtpHasher.otpHash(PEPPER, email, code); }

  @BeforeEach
  void noop() {}

  @Test
  void sendStoresHashedFixedCodeAndMailsIt() {
    service("12345").send("  Bob@Example.com ", "1.2.3.4");
    verify(limiter).check("otp-email:bob@example.com", 5, 3600);
    verify(limiter).check("otp-ip:1.2.3.4", 20, 3600);
    verify(repo).insert("bob@example.com", hash("bob@example.com", "12345"), NOW.plus(Duration.ofMinutes(5)), NOW);
    verify(sender).send("bob@example.com", "12345");
  }

  @Test
  void sendGeneratesFiveDigitCodeWhenNoFixedCode() {
    service("").send("a@b.com", "ip");
    var code = ArgumentCaptor.forClass(String.class);
    verify(sender).send(eq("a@b.com"), code.capture());
    assertThat(code.getValue()).matches("\\d{5}");
    verify(repo).insert("a@b.com", hash("a@b.com", code.getValue()), NOW.plus(Duration.ofMinutes(5)), NOW);
  }

  @Test
  void blankFixedCodeAndNullFixedCodeBothGenerateRandom() {
    service(null).send("a@b.com", "ip");
    var code = ArgumentCaptor.forClass(String.class);
    verify(sender).send(eq("a@b.com"), code.capture());
    assertThat(code.getValue()).hasSize(5);
  }

  @Test
  void fixedCodeWithStrayWhitespaceStillMatchesWhatTheUserTypes() {
    service(" 12345\n").send("bob@example.com", "1.2.3.4");
    verify(sender).send("bob@example.com", "12345");
    verify(repo).insert("bob@example.com", hash("bob@example.com", "12345"), NOW.plus(Duration.ofMinutes(5)), NOW);
  }

  @Test
  void whitespaceOnlyFixedCodeMeansRandomCodes() {
    service("   ").send("a@b.com", "ip");
    var code = ArgumentCaptor.forClass(String.class);
    verify(sender).send(eq("a@b.com"), code.capture());
    assertThat(code.getValue()).matches("\\d{5}");
  }

  @Test
  void missingOtpSettingsMeanRandomCodes() {
    var props = new AppProps(null, new AppProps.Otp("console", "test-pepper-test-pepper", null, "x@y.z"), null, null, null, null);
    new OtpService(repo, sender, props, clock, limiter, 20).send("a@b.com", "ip");
    var code = ArgumentCaptor.forClass(String.class);
    verify(sender).send(eq("a@b.com"), code.capture());
    assertThat(code.getValue()).hasSize(5);
  }

  @Test
  void sendStopsWhenRateLimited() {
    doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "slow down"))
        .when(limiter).check(eq("otp-email:a@b.com"), anyInt(), anyLong());
    assertThatThrownBy(() -> service("1").send("a@b.com", "ip")).isInstanceOf(ApiException.class);
    verify(sender, never()).send(any(), any());
    verify(repo, never()).insert(any(), any(), any(), any());
  }

  @Test
  void verifyConsumesChallengeOnRightCode() {
    UUID id = UUID.randomUUID();
    when(repo.latestOpen("a@b.com")).thenReturn(Optional.of(
        new OtpRepository.Challenge(id, hash("a@b.com", "12345"), NOW.plusSeconds(60), 0)));
    service("x").verify("A@B.com", " 12345 ");
    verify(repo).consume(id);
    verify(repo, never()).addAttempt(any());
  }

  @Test
  void wrongCodeCountsAnAttemptAndReportsWrong() {
    UUID id = UUID.randomUUID();
    when(repo.latestOpen("a@b.com")).thenReturn(Optional.of(
        new OtpRepository.Challenge(id, hash("a@b.com", "12345"), NOW.plusSeconds(60), 2)));
    assertThatThrownBy(() -> service("x").verify("a@b.com", "00000"))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo("OTP_INVALID");
          assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
          assertThat(e.details()).containsEntry("reason", "WRONG");
        });
    verify(repo).addAttempt(id);
    verify(repo, never()).consume(any());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  "})
  void missingCodeIsJustWrong(String code) {
    when(repo.latestOpen("a@b.com")).thenReturn(Optional.of(
        new OtpRepository.Challenge(UUID.randomUUID(), hash("a@b.com", "12345"), NOW.plusSeconds(60), 0)));
    assertThatThrownBy(() -> service("x").verify("a@b.com", code))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsEntry("reason", "WRONG"));
  }

  @Test
  void noOpenChallengeMeansExpired() {
    when(repo.latestOpen("a@b.com")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service("x").verify("a@b.com", "12345"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsEntry("reason", "EXPIRED"));
  }

  @Test
  void expiredChallengeIsRejectedEvenWithRightCode() {
    when(repo.latestOpen("a@b.com")).thenReturn(Optional.of(
        new OtpRepository.Challenge(UUID.randomUUID(), hash("a@b.com", "12345"), NOW.minusSeconds(1), 0)));
    assertThatThrownBy(() -> service("x").verify("a@b.com", "12345"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsEntry("reason", "EXPIRED"));
    verify(repo, never()).consume(any());
  }

  @Test
  void challengeThatExpiresExactlyNowIsStillValid() {
    UUID id = UUID.randomUUID();
    when(repo.latestOpen("a@b.com")).thenReturn(Optional.of(
        new OtpRepository.Challenge(id, hash("a@b.com", "12345"), NOW, 0)));
    service("x").verify("a@b.com", "12345");
    verify(repo).consume(id);
  }

  @Test
  void fifthWrongTryLocksEvenTheRightCode() {
    when(repo.latestOpen("a@b.com")).thenReturn(Optional.of(
        new OtpRepository.Challenge(UUID.randomUUID(), hash("a@b.com", "12345"), NOW.plusSeconds(60), OtpService.MAX_ATTEMPTS)));
    assertThatThrownBy(() -> service("x").verify("a@b.com", "12345"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsEntry("reason", "LOCKED"));
    verify(repo, never()).consume(any());
  }

  @Test
  void fourthAttemptIsStillAllowed() {
    UUID id = UUID.randomUUID();
    when(repo.latestOpen("a@b.com")).thenReturn(Optional.of(
        new OtpRepository.Challenge(id, hash("a@b.com", "12345"), NOW.plusSeconds(60), OtpService.MAX_ATTEMPTS - 1)));
    service("x").verify("a@b.com", "12345");
    verify(repo).consume(id);
  }

  @Test
  void cleanupDeletesChallengesOlderThanTwoDays() {
    service("x").cleanup();
    verify(repo).deleteOlderThan(NOW.minus(Duration.ofDays(2)));
  }
}
