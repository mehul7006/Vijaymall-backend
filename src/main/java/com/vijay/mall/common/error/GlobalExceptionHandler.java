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

  /** A file upload that is not sent as multipart/form-data: the caller's mistake (400), not a server fault. */
  @ExceptionHandler(MultipartException.class)
  ResponseEntity<ErrorBody> notMultipart(MultipartException e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Send the file as multipart/form-data in a part named file", null);
  }

  /**
   * The client closed the connection while a response was being written (a phone going to the background, a dropped network,
   * a live stream closed). Nothing can be sent any more and nothing is wrong on the server, so this is not an error.
   */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  void clientGone(AsyncRequestNotUsableException e, HttpServletResponse response) {
    log.debug("Client disconnected: {}", e.getMessage());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorBody> tooBig(MaxUploadSizeExceededException e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "File too large", null);
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
    log.error("Unhandled error", e);
    String type = response == null ? null : response.getContentType();
    if (response != null && (response.isCommitted() || (type != null && type.startsWith("text/event-stream")))) return null;
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong", null);
  }

  /** Overload kept for callers that have no response at hand. */
  ResponseEntity<ErrorBody> other(Exception e) { return other(e, null); }
}
