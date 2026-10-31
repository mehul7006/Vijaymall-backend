package com.vijay.mall.common.error;

import com.vijay.mall.common.web.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static String trace() { return MDC.get(TraceIdFilter.KEY); }

  private static ResponseEntity<ErrorBody> body(HttpStatus s, String code, String msg, Map<String, Object> d) {
    return ResponseEntity.status(s).body(new ErrorBody(code, msg, trace(), d == null ? Map.of() : d));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorBody> api(ApiException e) {
    if (e.status().is5xxServerError()) log.error("Request failed: {} {}", e.code(), e.getMessage());
    else log.info("Request rejected: {} {}", e.code(), e.getMessage());
    return body(e.status(), e.code(), e.getMessage(), e.details());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorBody> invalid(MethodArgumentNotValidException e) {
    Map<String, Object> fields = new LinkedHashMap<>();
    e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please check the highlighted fields",
        Map.of("fields", fields));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorBody> constraint(ConstraintViolationException e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage(), null);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
      MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
  ResponseEntity<ErrorBody> badRequest(Exception e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Bad request", null);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ErrorBody> noRoute(NoResourceFoundException e) {
    return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", null);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorBody> wrongMethod(HttpRequestMethodNotSupportedException e) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", null);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorBody> wrongMedia(HttpMediaTypeNotSupportedException e) {
    return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type", null);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorBody> tooBig(MaxUploadSizeExceededException e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "File too large", null);
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  ResponseEntity<ErrorBody> denied(org.springframework.security.access.AccessDeniedException e) {
    return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed", null);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorBody> other(Exception e) {
    log.error("Unhandled error", e);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong", null);
  }
}
