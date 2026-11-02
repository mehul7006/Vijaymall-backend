package com.vijay.mall.identity.app;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.identity.domain.OtpHasher;
import com.vijay.mall.identity.infra.OtpRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OtpService {
  private static final Logger log = LoggerFactory.getLogger(OtpService.class);
  static final int MAX_ATTEMPTS = 5;

  private final OtpRepository repo;
  private final OtpSender sender;
  private final AppProps props;
  private final Clock clock;
  private final RateLimiter limiter;
  private final int ipPerHour;
  private final SecureRandom random = new SecureRandom();

  public OtpService(OtpRepository repo, OtpSender sender, AppProps props, Clock clock, RateLimiter limiter,
                    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.otp-per-ip-hour:20}") int ipPerHour) {
    this.ipPerHour = ipPerHour;
    this.repo = repo;
    this.sender = sender;
    this.props = props;
    this.clock = clock;
    this.limiter = limiter;
    if (fixedCode().isPresent()) {
      log.warn("OTP fixed code is ACTIVE: every login code is the configured OTP_FIXED_CODE. Never use this in production.");
    } else {
      log.info("OTP fixed code is not set: login codes are random (set OTP_FIXED_CODE, or run the dev profile, for a fixed code).");
    }
  }

  /** The configured fixed code, trimmed; empty when none is set. */
  private java.util.Optional<String> fixedCode() {
    return java.util.Optional.ofNullable(props.otp()).map(AppProps.Otp::fixedCode).map(String::trim).filter(c -> !c.isEmpty());
  }

  public static String normalize(String email) { return email.trim().toLowerCase(); }

  @Transactional
  public void send(String rawEmail, String ip) {
    String email = normalize(rawEmail);
    limiter.check("otp-email:" + email, 5, 3600);
    limiter.check("otp-ip:" + ip, ipPerHour, 3600);
    String code = fixedCode().orElseGet(() -> String.format("%05d", random.nextInt(100000)));
    Instant now = clock.instant();
    repo.insert(email, OtpHasher.otpHash(props.otp().pepper(), email, code), now.plus(Duration.ofMinutes(5)), now);
    sender.send(email, code);
    log.info("OTP issued to {}", mask(email));
  }

  /** Checks the code; throws OTP_INVALID with a reason, otherwise consumes the challenge. */
  @Transactional(noRollbackFor = ApiException.class)
  public void verify(String rawEmail, String code) {
    String email = normalize(rawEmail);
    var ch = repo.latestOpen(email).orElseThrow(() -> invalid("EXPIRED"));
    if (ch.expiresAt().isBefore(clock.instant())) throw invalid("EXPIRED");
    if (ch.attempts() >= MAX_ATTEMPTS) throw invalid("LOCKED");
    String hash = OtpHasher.otpHash(props.otp().pepper(), email, code == null ? "" : code.trim());
    if (!OtpHasher.constantTimeEquals(hash, ch.otpHash())) {
      repo.addAttempt(ch.id());
      log.warn("OTP wrong code for {} attempt={}", mask(email), ch.attempts() + 1);
      throw invalid("WRONG");
    }
    repo.consume(ch.id());
  }

  /** a***@example.com : keeps the log useful without storing the full address. */
  static String mask(String email) {
    int at = email.indexOf('@');
    return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
  }

  private static ApiException invalid(String reason) {
    return ApiException.unprocessable("OTP_INVALID", "The code is not valid", Map.of("reason", reason));
  }

  @Scheduled(cron = "0 30 3 * * *")
  void cleanup() {
    repo.deleteOlderThan(clock.instant().minus(Duration.ofDays(2)));
  }
}
