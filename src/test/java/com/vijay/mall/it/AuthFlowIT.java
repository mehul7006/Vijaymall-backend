package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Email OTP login, token refresh with reuse detection, logout. */
class AuthFlowIT extends IntegrationTestBase {

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Map verify(String email, String code) {
    return post("/api/v1/auth/otp/verify", null, Map.of("email", email, "otp", code, "deviceId", UUID.randomUUID().toString())).getBody();
  }

  @Test
  void wrongCodeIsRejectedWithReason() {
    String email = freshEmail();
    post("/api/v1/auth/otp/send", null, Map.of("email", email));
    var res = post("/api/v1/auth/otp/verify", null, Map.of("email", email, "otp", "00000"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(res.getBody().get("code")).isEqualTo("OTP_INVALID");
  }

  @Test
  @SuppressWarnings("unchecked")
  void lockedAfterTooManyWrongTries() {
    String email = freshEmail();
    post("/api/v1/auth/otp/send", null, Map.of("email", email));
    IntStream.range(0, 5).forEach(_ -> verify(email, "00000"));
    var details = (Map<String, Object>) verify(email, "12345").get("details");
    assertThat(details).containsEntry("reason", "LOCKED");
  }

  @Test
  @SuppressWarnings("unchecked")
  void refreshRotatesAndReuseKillsTheSession() {
    Map<String, Object> auth = login(freshEmail());
    String old = (String) auth.get("refreshToken");

    var rotated = post("/api/v1/auth/refresh", null, Map.of("refreshToken", old));
    assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
    String fresh = (String) rotated.getBody().get("refreshToken");
    assertThat(fresh).isNotEqualTo(old);

    // presenting the old token again looks like theft: it fails and the new token stops working too
    assertThat(post("/api/v1/auth/refresh", null, Map.of("refreshToken", old)).getStatusCode().is4xxClientError()).isTrue();
    assertThat(post("/api/v1/auth/refresh", null, Map.of("refreshToken", fresh)).getStatusCode().is4xxClientError()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void logoutRevokesRefreshToken() {
    String refresh = (String) login(freshEmail()).get("refreshToken");
    assertThat(post("/api/v1/auth/logout", null, Map.of("refreshToken", refresh)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(post("/api/v1/auth/refresh", null, Map.of("refreshToken", refresh)).getStatusCode().is4xxClientError()).isTrue();
  }

  @Test
  void invalidEmailIsRejected() {
    assertThat(post("/api/v1/auth/otp/send", null, Map.of("email", "not-an-email")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
