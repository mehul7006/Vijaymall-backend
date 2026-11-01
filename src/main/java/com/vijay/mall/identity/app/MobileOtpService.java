package com.vijay.mall.identity.app;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.identity.domain.MobileNumbers;
import com.vijay.mall.identity.domain.OtpHasher;
import com.vijay.mall.identity.infra.OtpRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One-time codes for mobile sign-in. Same rules as the email codes: 5 minutes, 5 tries, hashed with a pepper. */
@Service
public class MobileOtpService {
  private static final Logger log = LoggerFactory.getLogger(MobileOtpService.class);
  static final int MAX_ATTEMPTS = 5;

  private final OtpRepository repo;
  private final SmsOtpSender sender;
  private final AppProps props;
  private final Clock clock;
  private final RateLimiter limiter;
  private final int ipPerHour;
  private final SecureRandom random = new SecureRandom();

  public MobileOtpService(OtpRepository repo, SmsOtpSender sender, AppProps props, Clock clock, RateLimiter limiter,
                          @Value("${app.rate-limit.otp-per-ip-hour:20}") int ipPerHour) {
    this.repo = repo;
    this.sender = sender;
    this.props = props;
    this.clock = clock;
    this.limiter = limiter;
    this.ipPerHour = ipPerHour;
  }

  /** The 10-digit form of what the person typed, or a validation error. */
  public static String require(String raw) {
    return MobileNumbers.normalize(raw)
        .orElseThrow(() -> ApiException.validation("mobile", "must be a valid 10-digit mobile number"));
  }

  /** The hash input names the channel, so a code for a mobile can never match an email code. */
  static String hashKey(String mobile) { return "mobile:" + mobile; }

  @Transactional
  public void send(String rawMobile, String ip) {
    String mobile = require(rawMobile);
    // SMS costs money: a tight limit per number and the same per-address budget as email codes.
    limiter.check("otp-mobile:" + mobile, 5, 3600);
    limiter.check("otp-ip:" + ip, ipPerHour, 3600);
    String fixed = props.otp().fixedCode();
    String code = (fixed != null && !fixed.isBlank()) ? fixed : String.format("%05d", random.nextInt(100000));
    Instant now = clock.instant();
    repo.insertMobile(mobile, OtpHasher.otpHash(props.otp().pepper(), hashKey(mobile), code),
        now.plus(Duration.ofMinutes(5)), now);
    sender.send(mobile, code); // if no SMS provider is configured this throws and the stored code is rolled back
    log.info("Mobile OTP issued to {}", mask(mobile));
  }

  /** Checks the code; throws OTP_INVALID with a reason, otherwise consumes the challenge. */
  @Transactional(noRollbackFor = ApiException.class)
  public void verify(String rawMobile, String code) {
    String mobile = require(rawMobile);
    var ch = repo.latestOpenMobile(mobile).orElseThrow(() -> invalid("EXPIRED"));
    if (ch.expiresAt().isBefore(clock.instant())) throw invalid("EXPIRED");
    if (ch.attempts() >= MAX_ATTEMPTS) throw invalid("LOCKED");
    String hash = OtpHasher.otpHash(props.otp().pepper(), hashKey(mobile), code == null ? "" : code.trim());
    if (!OtpHasher.constantTimeEquals(hash, ch.otpHash())) {
      repo.addAttempt(ch.id());
      log.warn("Mobile OTP wrong code for {} attempt={}", mask(mobile), ch.attempts() + 1);
      throw invalid("WRONG");
    }
    repo.consume(ch.id());
  }

  /** 98******10 : keeps the log useful without storing the full number. */
  static String mask(String mobile) { return mobile.substring(0, 2) + "******" + mobile.substring(8); }

  private static ApiException invalid(String reason) {
    return ApiException.unprocessable("OTP_INVALID", "The code is not valid", Map.of("reason", reason));
  }
}
