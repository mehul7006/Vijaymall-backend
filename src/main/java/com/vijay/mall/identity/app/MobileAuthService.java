package com.vijay.mall.identity.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in with a mobile number. The account is found by the number the person has proven, else a counter customer
 * with that number is claimed, else a new customer is made. Admin and cashier accounts keep signing in by email.
 */
@Service
public class MobileAuthService {
  private static final Logger log = LoggerFactory.getLogger(MobileAuthService.class);

  private final MobileOtpService otp;
  private final TokenService tokens;
  private final UserRepository users;
  private final Clock clock;

  /** Creates the service with the mobile OTP and token services and the user repository (wired by Spring). */
  public MobileAuthService(MobileOtpService otp, TokenService tokens, UserRepository users, Clock clock) {
    this.otp = otp;
    this.tokens = tokens;
    this.users = users;
    this.clock = clock;
  }

  /**
   * Verifies an SMS code and signs the user in, creating a customer when the number is new.
   * @param rawMobile the number as typed; normalised to 10 digits
   * @param code the code received by SMS
   * @param deviceId optional device id stored with the refresh token
   * @return fresh access and refresh tokens
   * @throws com.vijay.mall.common.error.ApiException 422 {@code OTP_INVALID} for a bad code, 403 for a blocked user
   */
  @Transactional(noRollbackFor = ApiException.class)
  public TokenService.AuthResult verify(String rawMobile, String code, String deviceId) {
    String mobile = MobileOtpService.require(rawMobile);
    otp.verify(mobile, code);
    User user = users.findByVerifiedMobile(mobile)
        .or(() -> users.adoptCounterCustomer(mobile))
        .orElseGet(() -> users.insertMobileCustomer(mobile, clock.instant()));
    if (users.isBlocked(user.id())) {
      log.warn("Blocked user tried to log in user={}", user.id());
      throw ApiException.forbidden();
    }
    log.info("Mobile login ok user={} role={}", user.id(), user.role());
    return tokens.issue(user, deviceId);
  }
}
