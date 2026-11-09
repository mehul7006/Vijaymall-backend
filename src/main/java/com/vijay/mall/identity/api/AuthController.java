package com.vijay.mall.identity.api;

import com.vijay.mall.common.ratelimit.RateLimitFilter;
import com.vijay.mall.identity.app.AuthService;
import com.vijay.mall.identity.app.MobileAuthService;
import com.vijay.mall.identity.app.MobileOtpService;
import com.vijay.mall.identity.app.OtpService;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.app.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public sign-in endpoints under {@code /api/v1/auth}: passwordless one-time codes by e-mail or by mobile number,
 * token refresh and logout.
 * <p>
 * Nobody has a password. The app asks for a code, the customer types it back, and the server answers with a JWT access
 * token and a long-lived refresh token. All paths here are open to everyone (no token needed, see SecurityConfig);
 * abuse is limited by the per-address and per-IP rate limits inside the OTP services.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  /**
   * Request body to ask for an e-mail sign-in code.
   * @param email the address the code is sent to; any letter case, it is trimmed and lower-cased by the server
   */
  public record SendOtp(@NotBlank @Email String email) {}
  /**
   * Request body to finish an e-mail sign-in.
   * @param email the address the code was sent to
   * @param otp the 5-digit code the customer received
   * @param deviceId optional id of the phone or browser; refresh tokens are grouped per device
   */
  public record VerifyOtp(@NotBlank @Email String email, @NotBlank String otp, String deviceId) {}
  /**
   * Request body to ask for an SMS sign-in code.
   * @param mobile the mobile number as typed; {@code +91}, spaces and a leading zero are accepted
   */
  public record SendMobileOtp(@NotBlank String mobile) {}
  /**
   * Request body to finish a mobile sign-in.
   * @param mobile the mobile number the code was sent to
   * @param otp the 5-digit code received by SMS
   * @param deviceId optional id of the phone or browser; refresh tokens are grouped per device
   */
  public record VerifyMobileOtp(@NotBlank String mobile, @NotBlank String otp, String deviceId) {}
  /**
   * Request body that carries a refresh token, used by both refresh and logout.
   * @param refreshToken the opaque refresh token returned at sign-in
   */
  public record RefreshReq(@NotBlank String refreshToken) {}

  /**
   * Request body to sign out.
   * @param refreshToken the opaque refresh token to revoke
   * @param fcmToken optional push token of this phone; when given it is unregistered for the signed-out user
   */
  public record LogoutReq(@NotBlank String refreshToken, String fcmToken) {}

  private final OtpService otp;
  private final AuthService auth;
  private final TokenService tokens;
  private final MobileOtpService mobileOtp;
  private final MobileAuthService mobileAuth;
  private final ProfileService profile;

  /** Creates the controller with the e-mail and mobile OTP, authentication and token services (wired by Spring). */
  public AuthController(OtpService otp, AuthService auth, TokenService tokens, MobileOtpService mobileOtp,
                        MobileAuthService mobileAuth, ProfileService profile) {
    this.profile = profile;
    this.otp = otp;
    this.auth = auth;
    this.tokens = tokens;
    this.mobileOtp = mobileOtp;
    this.mobileAuth = mobileAuth;
  }

  /**
   * Sends a sign-in code to an e-mail address; answers 204 with no body.
   * <p>
   * Fails with 429 {@code RATE_LIMITED} when the address or the caller's IP asked for too many codes in the last hour.
   */
  @PostMapping("/otp/send")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void send(@Valid @RequestBody SendOtp body, HttpServletRequest req) {
    otp.send(body.email(), RateLimitFilter.clientIp(req));
  }

  /**
   * Checks an e-mail code and signs the customer in, creating the account on first use.
   * <p>
   * Fails with 422 {@code OTP_INVALID} (reason {@code EXPIRED}, {@code WRONG} or {@code LOCKED}) and with 403 when the
   * account is blocked.
   * @return the access token, refresh token, access-token lifetime in seconds and the signed-in user
   */
  @PostMapping("/otp/verify")
  public TokenService.AuthResult verify(@Valid @RequestBody VerifyOtp body) {
    return auth.verify(body.email(), body.otp(), body.deviceId());
  }

  /**
   * Sends a sign-in code by SMS; answers 204 with no body.
   * <p>
   * Fails with 422 when the number is not a valid 10-digit Indian mobile, 503 {@code SMS_NOT_CONFIGURED} when no SMS
   * provider is set up, and 429 {@code RATE_LIMITED} when too many codes were asked for.
   */
  @PostMapping("/otp/send-mobile")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void sendMobile(@Valid @RequestBody SendMobileOtp body, HttpServletRequest req) {
    mobileOtp.send(body.mobile(), RateLimitFilter.clientIp(req));
  }

  /**
   * Checks an SMS code and signs the customer in. The account is found by the proven number, or a counter customer
   * with that number is claimed, or a new customer is created. Errors are the same as for the e-mail verify call.
   */
  @PostMapping("/otp/verify-mobile")
  public TokenService.AuthResult verifyMobile(@Valid @RequestBody VerifyMobileOtp body) {
    return mobileAuth.verify(body.mobile(), body.otp(), body.deviceId());
  }

  /**
   * Swaps a refresh token for a new access token and a new refresh token (the old one stops working).
   * <p>
   * Fails with 401 {@code UNAUTHENTICATED} for an unknown, expired or already used token; reusing a used token also
   * signs out that whole device.
   */
  @PostMapping("/refresh")
  public TokenService.AuthResult refresh(@Valid @RequestBody RefreshReq body) {
    return tokens.refresh(body.refreshToken());
  }

  /**
   * Signs the device out by revoking the given refresh token; answers 204 even when the token is unknown. When an
   * {@code fcmToken} is sent too, that push token is unregistered for the user the refresh token belongs to.
   */
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@Valid @RequestBody LogoutReq body) {
    tokens.logout(body.refreshToken()).ifPresent(userId -> {
      if (body.fcmToken() != null && !body.fcmToken().isBlank()) profile.unregisterDevice(userId, body.fcmToken());
    });
  }
}
