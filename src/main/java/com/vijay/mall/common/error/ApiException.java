package com.vijay.mall.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Business error that maps to the contract error shape. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final Map<String, Object> details;

  public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details == null ? Map.of() : details;
  }

  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, Map.of());
  }

  public HttpStatus status() { return status; }
  public String code() { return code; }
  public Map<String, Object> details() { return details; }

  public static ApiException notFound(String what) {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
  }
  public static ApiException unauthenticated() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in again");
  }
  public static ApiException forbidden() {
    return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
  }
  public static ApiException validation(String field, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message,
        Map.of("fields", Map.of(field, message)));
  }
  public static ApiException conflict(String code, String message, Map<String, Object> details) {
    return new ApiException(HttpStatus.CONFLICT, code, message, details);
  }
  public static ApiException unprocessable(String code, String message, Map<String, Object> details) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
  }
}
