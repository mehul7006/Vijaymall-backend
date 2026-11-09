package com.vijay.mall.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartException;

/** Every 429 carries the standard Retry-After header; upload errors name the file field. */
class RetryAfterTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void aRateLimitedAnswerSendsRetryAfterInSeconds() {
    var r = handler.api(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "slow", Map.of("retryAfterSeconds", 42L)));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("42");
    assertThat(r.getBody().details()).containsEntry("retryAfterSeconds", 42L);
  }

  @Test
  void aTooManyRequestsWithoutAWaitTimeAndOtherErrorsSendNoHeader() {
    var noWait = handler.api(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "slow"));
    assertThat(noWait.getHeaders().containsHeader("Retry-After")).isFalse();
    var other = handler.api(new ApiException(HttpStatus.CONFLICT, "X", "x", Map.of("retryAfterSeconds", 5)));
    assertThat(other.getHeaders().containsHeader("Retry-After")).isFalse();
    assertThat(ApiException.notFound("x").retryAfterSeconds()).isNull();
  }

  @Test
  void fieldErrorUsesTheValidationShapeWithItsOwnCode() {
    var e = ApiException.fieldError("UNSUPPORTED_IMAGE", "file", "must be an image");
    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.code()).isEqualTo("UNSUPPORTED_IMAGE");
    assertThat(e.details()).isEqualTo(Map.of("fields", Map.of("file", "must be an image")));
  }

  @Test
  void aNonMultipartUploadNamesTheFileField() {
    var r = handler.notMultipart(new MultipartException("x"));
    assertThat(r.getBody().details().toString()).contains("file");
  }
}
