package com.vijay.mall.identity.app;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E-mail sign-in: checks the one-time code, finds or creates the account and issues tokens.
 * <p>
 * A new e-mail becomes a customer. The e-mail configured as the bootstrap admin ({@code app.bootstrap-admin-email})
 * is made ADMIN when it signs in, which is how the first administrator appears. Blocked users are refused.
 */
@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  private final OtpService otp;
  private final TokenService tokens;
  private final UserRepository users;
  private final AppProps props;
  private final Clock clock;

  /** Creates the service with the OTP and token services and the user repository (wired by Spring). */
  public AuthService(OtpService otp, TokenService tokens, UserRepository users, AppProps props, Clock clock) {
    this.otp = otp;
    this.tokens = tokens;
    this.users = users;
    this.props = props;
    this.clock = clock;
  }

  /**
   * Verifies the code and signs the user in.
   * @param email the address the code was sent to
   * @param code the code typed by the customer
   * @param deviceId optional device id stored with the refresh token
   * @return fresh access and refresh tokens
   * @throws com.vijay.mall.common.error.ApiException 422 {@code OTP_INVALID} for a bad code, 403 for a blocked user
   */
  @Transactional(noRollbackFor = ApiException.class)
  public TokenService.AuthResult verify(String email, String code, String deviceId) {
    otp.verify(email, code);
    String e = OtpService.normalize(email);
    User user = users.findByEmail(e).orElseGet(() -> users.insert(e, roleFor(e), clock.instant()));
    if (users.isBlocked(user.id())) {
      log.warn("Blocked user tried to log in user={}", user.id());
      throw ApiException.forbidden();
    }
    String boot = props.bootstrapAdminEmail();
    if (boot != null && !boot.isBlank() && boot.trim().equalsIgnoreCase(e) && !User.ADMIN.equals(user.role())) {
      users.setRole(user.id(), User.ADMIN);
      user = new User(user.id(), user.email(), user.name(), user.mobile(), User.ADMIN);
    }
    log.info("Login ok user={} role={}", user.id(), user.role());
    return tokens.issue(user, deviceId);
  }

  private String roleFor(String email) {
    String boot = props.bootstrapAdminEmail();
    return (boot != null && !boot.isBlank() && boot.trim().equalsIgnoreCase(email)) ? User.ADMIN : User.CUSTOMER;
  }
}
