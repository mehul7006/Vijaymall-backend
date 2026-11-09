package com.vijay.mall.common.error;

import com.vijay.mall.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Turns every exception into the standard {@link ErrorBody}: business errors keep their status and code, bad input becomes {@code 400 VALIDATION_FAILED}, database constraint failures become 400 or {@code 409 ALREADY_EXISTS}, and anything unexpected becomes {@code 500 INTERNAL_ERROR} without leaking details.
 */
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
    ResponseEntity.BodyBuilder out = ResponseEntity.status(e.status());
    Long retryAfter = e.retryAfterSeconds();
    if (retryAfter != null) out.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter)); // standard header for any 429
    return out.body(new ErrorBody(e.code(), e.getMessage(), trace(), e.details()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorBody> invalid(MethodArgumentNotValidException e) {
    Map<String, Object> fields = new LinkedHashMap<>();
    e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), FieldProblems.message(f)));
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please check the highlighted fields",
        Map.of("fields", fields));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorBody> constraint(ConstraintViolationException e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage(), null);
  }

  private static ResponseEntity<ErrorBody> fieldError(String message, String field, String problem) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, Map.of("fields", Map.of(field, problem)));
  }

  /**
   * A body that cannot be read: broken or empty JSON gives a plain message; a wrong type, a bad enum value or a null for a
   * required value names the field (JSON path such as {@code items[0].qty}) and what it must be.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
    JacksonException j = FieldProblems.jackson(e);
    if (FieldProblems.unreadable(j)) return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", FieldProblems.NOT_JSON, null);
    String field = FieldProblems.field(j);
    String problem = FieldProblems.describe((MismatchedInputException) j);
    return fieldError("Please check the highlighted fields", field, problem);
  }

  /** A path or query value of the wrong type, such as a bad id or a status that does not exist. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorBody> wrongType(MethodArgumentTypeMismatchException e) {
    return fieldError("Please check the highlighted fields", e.getName(), FieldProblems.mustBe(e.getRequiredType()));
  }

  /** A required header that was not sent. */
  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ErrorBody> missingHeader(MissingRequestHeaderException e) {
    return fieldError("Missing header " + e.getHeaderName(), e.getHeaderName(), FieldProblems.REQUIRED);
  }

  /** A required query parameter that was not sent. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorBody> missingParameter(MissingServletRequestParameterException e) {
    return fieldError("Please check the highlighted fields", e.getParameterName(), FieldProblems.REQUIRED);
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

  /** A file upload that is not sent as multipart/form-data: the caller's mistake (400), not a server fault. */
  @ExceptionHandler(MultipartException.class)
  ResponseEntity<ErrorBody> notMultipart(MultipartException e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Send the file as multipart/form-data in a part named file",
        Map.of("fields", Map.of("file", "send the file as multipart/form-data in a part named file")));
  }

  /**
   * The client closed the connection while a response was being written (a phone going to the background, a dropped network,
   * a live stream closed). Nothing can be sent any more and nothing is wrong on the server, so this is not an error.
   */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  void clientGone(AsyncRequestNotUsableException e, HttpServletResponse response) {
    log.debug("Client disconnected: {}", e.getMessage());
  }

  /** An upload over the multipart limit (5 MB): 400 {@code FILE_TOO_LARGE} with the message on {@code details.fields.file}. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorBody> tooBig(MaxUploadSizeExceededException e) {
    return body(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "File too large: must be 5 MB or smaller",
        Map.of("fields", Map.of("file", "must be 5 MB or smaller")));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ErrorBody> denied(AccessDeniedException e) {
    return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed", null);
  }

  /** Bad input that only the database can see: a missing referenced row is the caller's mistake (400), a duplicate is a conflict (409). */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ErrorBody> integrity(DataIntegrityViolationException e) {
    String state = sqlState(e);
    if ("23503".equals(state)) {
      log.info("Rejected: references something that does not exist: {}", e.getMostSpecificCause().getMessage());
      return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Something you referred to does not exist", null);
    }
    if ("23505".equals(state)) {
      log.info("Rejected: already exists: {}", e.getMostSpecificCause().getMessage());
      return body(HttpStatus.CONFLICT, "ALREADY_EXISTS", "This already exists", null);
    }
    return other(e);
  }

  private static String sqlState(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof SQLException s && s.getSQLState() != null) return s.getSQLState();
      if (c.getCause() == c) break;
    }
    return null;
  }

  /**
   * Anything not handled above is a server fault (500). When the response is already under way (a live event stream) there is
   * no JSON body to send any more: the failure is logged and the stream simply ends.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorBody> other(Exception e, HttpServletResponse response) {
    String type = response == null ? null : response.getContentType();
    boolean cannotAnswer = response != null && (response.isCommitted() || (type != null && type.startsWith("text/event-stream")));
    if (waitedForDatabaseConnection(e)) {
      log.warn("Database connection pool exhausted: {}", e.getMessage());
      if (cannotAnswer) return null;
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "2")
          .body(new ErrorBody("SERVICE_BUSY", "The shop is very busy right now. Please try again in a moment.", trace(), Map.of("retryAfterSeconds", 2)));
    }
    log.error("Unhandled error", e);
    if (cannotAnswer) return null;
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong", null);
  }

  /** Overload kept for callers that have no response at hand. */
  ResponseEntity<ErrorBody> other(Exception e) { return other(e, null); }

  /** True when the request failed only because no database connection became free in time (the pool is full): a busy signal, not a bug. */
  private static boolean waitedForDatabaseConnection(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof java.sql.SQLTransientConnectionException
          || t instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
          || t instanceof org.springframework.transaction.CannotCreateTransactionException) return true;
    }
    return false;
  }
}
