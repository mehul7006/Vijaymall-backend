package com.vijay.mall.identity.api;

import com.vijay.mall.common.ratelimit.RateLimitFilter;
import com.vijay.mall.identity.app.AuthService;
import com.vijay.mall.identity.app.OtpService;
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

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  public record SendOtp(@NotBlank @Email String email) {}
  public record VerifyOtp(@NotBlank @Email String email, @NotBlank String otp, String deviceId) {}
  public record RefreshReq(@NotBlank String refreshToken) {}

  private final OtpService otp;
  private final AuthService auth;
  private final TokenService tokens;

  public AuthController(OtpService otp, AuthService auth, TokenService tokens) {
    this.otp = otp;
    this.auth = auth;
    this.tokens = tokens;
  }

  @PostMapping("/otp/send")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void send(@Valid @RequestBody SendOtp body, HttpServletRequest req) {
    otp.send(body.email(), RateLimitFilter.clientIp(req));
  }

  @PostMapping("/otp/verify")
  public TokenService.AuthResult verify(@Valid @RequestBody VerifyOtp body) {
    return auth.verify(body.email(), body.otp(), body.deviceId());
  }

  @PostMapping("/refresh")
  public TokenService.AuthResult refresh(@Valid @RequestBody RefreshReq body) {
    return tokens.refresh(body.refreshToken());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@Valid @RequestBody RefreshReq body) {
    tokens.logout(body.refreshToken());
  }
}
