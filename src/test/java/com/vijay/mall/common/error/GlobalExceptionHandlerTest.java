package com.vijay.mall.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.web.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Every exception type maps to one stable code, status and the current trace id. */
class GlobalExceptionHandlerTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @AfterEach
  void clearMdc() { MDC.clear(); }

  private static void check(ResponseEntity<ErrorBody> r, HttpStatus status, String code) {
    assertThat(r.getStatusCode()).isEqualTo(status);
    assertThat(r.getBody()).isNotNull();
    assertThat(r.getBody().code()).isEqualTo(code);
    assertThat(r.getBody().details()).isNotNull();
  }

  @Test
  void apiExceptionKeepsItsStatusCodeMessageAndDetails() {
    MDC.put(TraceIdFilter.KEY, "abc12345");
    var r = handler.api(ApiException.conflict("OUT_OF_STOCK", "gone", Map.of("n", 1)));
    check(r, HttpStatus.CONFLICT, "OUT_OF_STOCK");
    assertThat(r.getBody().message()).isEqualTo("gone");
    assertThat(r.getBody().traceId()).isEqualTo("abc12345");
    assertThat(r.getBody().details()).containsEntry("n", 1);
  }

  @Test
  void serverSideApiExceptionStillReturnsItsBody() {
    check(handler.api(new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM", "down")), HttpStatus.BAD_GATEWAY, "UPSTREAM");
  }

  @Test
  void missingTraceIdIsNull() {
    assertThat(handler.api(ApiException.forbidden()).getBody().traceId()).isNull();
  }

  void sample(String body) {}

  @Test
  void beanValidationListsEachBadField() throws Exception {
    var br = new BeanPropertyBindingResult(new Object(), "body");
    br.addError(new FieldError("body", "email", "must not be blank"));
    br.addError(new FieldError("body", "otp", "size"));
    var param = new MethodParameter(getClass().getDeclaredMethod("sample", String.class), 0);
    var r = handler.invalid(new MethodArgumentNotValidException(param, br));
    check(r, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    assertThat(r.getBody().details()).containsEntry("fields", Map.of("email", "must not be blank", "otp", "size"));
  }

  @Test
  void constraintViolationIs400() {
    var r = handler.constraint(new ConstraintViolationException("bad value", Set.of()));
    check(r, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    assertThat(r.getBody().message()).isEqualTo("bad value");
  }

  @Test
  void unreadableBodyAndMissingParamAreBadRequest() {
    check(handler.badRequest(new HttpMessageNotReadableException("x", new MockHttpInputMessage(new byte[0]))),
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    check(handler.badRequest(new MissingServletRequestParameterException("q", "String")),
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
  }

  @Test
  void wrongMethodIs405() {
    check(handler.wrongMethod(new HttpRequestMethodNotSupportedException("PATCH")),
        HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
  }

  @Test
  void wrongContentTypeIs415() {
    check(handler.wrongMedia(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of())),
        HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void tooBigUploadIs400() {
    var r = handler.tooBig(new MaxUploadSizeExceededException(1024));
    check(r, HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE");
    assertThat(r.getBody().details()).isEqualTo(java.util.Map.of("fields", java.util.Map.of("file", "must be 5 MB or smaller")));
  }

  @Test
  void accessDeniedIs403() {
    check(handler.denied(new AccessDeniedException("no")), HttpStatus.FORBIDDEN, "FORBIDDEN");
  }

  @Test
  void anythingElseIsAGenericInternalErrorWithoutLeakingTheMessage() {
    var r = handler.other(new IllegalStateException("secret table name"));
    check(r, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    assertThat(r.getBody().message()).doesNotContain("secret");
  }

  @Test
  void databaseConstraintMistakesAreTheCallersNotServerErrors() {
    var handler = new GlobalExceptionHandler();
    var fk = new DataIntegrityViolationException("x", new SQLException("fk", "23503"));
    var dup = new DataIntegrityViolationException("x", new RuntimeException(new SQLException("dup", "23505")));
    var other = new DataIntegrityViolationException("x", new SQLException("check", "23514"));
    Assertions.assertThat(handler.integrity(fk).getStatusCode().value()).isEqualTo(400);
    Assertions.assertThat(handler.integrity(dup).getStatusCode().value()).isEqualTo(409);
    Assertions.assertThat(handler.integrity(other).getStatusCode().value()).isEqualTo(500);
  }
}
