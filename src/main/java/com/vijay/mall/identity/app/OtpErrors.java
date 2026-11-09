package com.vijay.mall.identity.app;

import com.vijay.mall.common.error.ApiException;
import java.util.Map;

/** The errors of the sign-in code flows (e-mail and SMS) with messages a customer can act on. The error codes never change. */
final class OtpErrors {
  private OtpErrors() {}

  /**
   * {@code 422 OTP_INVALID} for a reason: {@code WRONG} (typed wrongly), {@code EXPIRED} (no open code, or too old) or
   * {@code LOCKED} (too many wrong tries). The reason stays in {@code details.reason}; the message is specific to it.
   */
  static ApiException invalid(String reason) {
    String message = switch (reason) {
      case "WRONG" -> "That code is not right. Check it and try again.";
      case "EXPIRED" -> "That code has expired. Request a new one.";
      default -> "Too many wrong attempts. Request a new code.";
    };
    return ApiException.unprocessable("OTP_INVALID", message, Map.of("reason", reason));
  }

  /** The same 429 {@code RATE_LIMITED} (and {@code retryAfterSeconds}) with a message that says what to do. */
  static ApiException tooManyCodes(ApiException limited) {
    Long seconds = limited.retryAfterSeconds();
    String message = seconds == null ? "Too many codes requested. Try again later."
        : "Too many codes requested. Try again in " + seconds + " seconds.";
    return new ApiException(limited.status(), limited.code(), message, limited.details());
  }
}
