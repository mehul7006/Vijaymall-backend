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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time sign-in codes sent by e-mail.
 * <p>
 * A code is 5 digits and valid for 5 minutes, at most 5 wrong tries are allowed, and only an HMAC hash (with a secret
 * pepper) is stored. Asking for codes is limited per e-mail address and per client IP. A configured fixed code
 * replaces random codes for development and testing and must never be used in production.
 */
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
  private int perEmailHour = 5;

  /** Codes one e-mail address may ask for per hour (production keeps 5; tests that sign in all day raise it). */
  @Autowired(required = false)
  void setPerEmailHour(@Value("${app.rate-limit.otp-per-email-hour:5}") int n) {
    this.perEmailHour = n;
  }

  /**
   * Creates the service (wired by Spring).
   * @param ipPerHour how many codes one client IP may ask for per hour
   */
  public OtpService(OtpRepository repo, OtpSender sender, AppProps props, Clock clock, RateLimiter limiter,
                    @Value("${app.rate-limit.otp-per-ip-hour:20}") int ipPerHour) {
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
  private Optional<String> fixedCode() {
    return Optional.ofNullable(props.otp()).map(AppProps.Otp::fixedCode).map(String::trim).filter(c -> !c.isEmpty());
  }

  /** Trims and lower-cases an e-mail address so the same person always maps to the same account. */
  public static String normalize(String email) { return email.trim().toLowerCase(); }

  /**
   * Creates a code for the address, stores only its hash and e-mails it.
   * <p>
   * Fails with 429 {@code RATE_LIMITED} when the address or the IP asked for too many codes in the last hour.
   * @param rawEmail the address as typed
   * @param ip the caller's IP address, used for rate limiting
   */
  @Transactional
  public void send(String rawEmail, String ip) {
    String email = normalize(rawEmail);
    limiter.check("otp-email:" + email, perEmailHour, 3600);
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
