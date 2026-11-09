package com.vijay.mall.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Business error that maps to the contract error shape. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final Map<String, Object> details;

  /**
   * Creates an error with extra details for the client; {@code null} details become empty.
   * @param status the HTTP status
   * @param code the stable error code the apps switch on, for example {@code OUT_OF_STOCK}
   * @param message a human-readable message
   * @param details extra values for the client, for example a fresh quote
   */
  public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details == null ? Map.of() : details;
  }

  /** Creates an error without details. */
  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, Map.of());
  }

  /** The HTTP status to answer with. */
  public HttpStatus status() { return status; }
  /** The stable error code. */
  public String code() { return code; }
  /** Extra values for the client; never {@code null}. */
  public Map<String, Object> details() { return details; }

  /** {@code 404 NOT_FOUND} with the message "{what} not found". */
  public static ApiException notFound(String what) {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
  }
  /** {@code 401 UNAUTHENTICATED}: the caller must sign in again. */
  public static ApiException unauthenticated() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in again");
  }
  /** {@code 403 FORBIDDEN}: the caller is signed in but not allowed. */
  public static ApiException forbidden() {
    return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
  }
  /** {@code 400 VALIDATION_FAILED} naming the field and the problem, in the same shape bean validation uses. */
  public static ApiException validation(String field, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message,
        Map.of("fields", Map.of(field, message)));
  }
  /**
   * {@code 400} with a specific code, naming the field and the problem in the same {@code details.fields} shape bean validation uses
   * (for example {@code UNSUPPORTED_IMAGE} on field {@code file}).
   */
  public static ApiException fieldError(String code, String field, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message, Map.of("fields", Map.of(field, message)));
  }
  /** Seconds to wait before retrying when this is a 429 that says so ({@code details.retryAfterSeconds}); otherwise {@code null}. */
  public Long retryAfterSeconds() {
    return status == HttpStatus.TOO_MANY_REQUESTS && details.get("retryAfterSeconds") instanceof Number n ? n.longValue() : null;
  }
  /** {@code 409} with a specific code (for example {@code ILLEGAL_TRANSITION}) and details. */
  public static ApiException conflict(String code, String message, Map<String, Object> details) {
    return new ApiException(HttpStatus.CONFLICT, code, message, details);
  }
  /** {@code 422} with a specific code (for example {@code ORDERS_PAUSED}) and details. */
  public static ApiException unprocessable(String code, String message, Map<String, Object> details) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
  }
}
