package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Real HTTP: every kind of bad request gets the same error envelope and a trace id. */
class ErrorHandlingIT extends IntegrationTestBase {

  @Test
  @SuppressWarnings("rawtypes")
  void malformedJsonIs400WithEnvelopeAndTraceHeader() {
    var res = client().post().uri("/api/v1/auth/otp/send").contentType(MediaType.APPLICATION_JSON).body("{not json")
        .retrieve().toEntity(Map.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(res.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    assertThat(res.getHeaders().getFirst("X-Trace-Id")).isNotBlank().isEqualTo(res.getBody().get("traceId"));
  }

  @Test
  @SuppressWarnings("rawtypes")
  void wrongContentTypeIs415() {
    var res = client().post().uri("/api/v1/auth/otp/send").contentType(MediaType.TEXT_PLAIN).body("hello")
        .retrieve().toEntity(Map.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(res.getBody().get("code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void wrongMethodIs405() {
    var res = call(HttpMethod.DELETE, "/api/v1/products", token(freshEmail()), null, null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(res.getBody().get("code")).isEqualTo("METHOD_NOT_ALLOWED");
  }

  @Test
  void badUuidInPathIs400() {
    var res = get("/api/v1/products/not-a-uuid", null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void missingRequiredHeaderIs400() {
    var res = post("/api/v1/orders", token(freshEmail()), Map.of("items", List.of(), "paymentMethod", "COD"));
    assertThat(res.getStatusCode().is4xxClientError()).isTrue();
  }

  @Test
  void validationErrorsListTheFields() {
    var res = post("/api/v1/auth/otp/verify", null, Map.of("email", "", "otp", ""));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    var fields = (Map<String, Object>) ((Map<String, Object>) res.getBody().get("details")).get("fields");
    assertThat(fields).containsKeys("email", "otp");
  }

  @Test
  void unknownImageIs404AndHealthIsOnThePrivatePortOnly() {
    assertThat(get("/img/does-not-exist.jpg", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var health = RestClient.builder().baseUrl("http://localhost:" + managementPort)
        .defaultStatusHandler(_ -> true, (_, _) -> { }).build().get().uri("/actuator/health").retrieve().toEntity(String.class);
    assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/actuator/health", null).getStatusCode()).as("not on the public port any more").isEqualTo(HttpStatus.NOT_FOUND);
  }
}
