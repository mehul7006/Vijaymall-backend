package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Regression: a name-only profile save must not strip the only way a mobile-only customer can sign in. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ProfileUpdateIT extends IntegrationTestBase {

  @Test
  void savingTheNameOfAMobileOnlyCustomerKeepsTheirNumberEvenIfItIsNotVerified() {
    String mobile = "9" + String.format("%09d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000L);
    post("/api/v1/auth/otp/send-mobile", null, Map.of("mobile", mobile));
    var login = post("/api/v1/auth/otp/verify-mobile", null, Map.of("mobile", mobile, "otp", "12345", "deviceId", UUID.randomUUID().toString()));
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = (String) login.getBody().get("accessToken");
    jdbc.update("update users set mobile_verified = false where mobile = ?", mobile);

    var saved = call(org.springframework.http.HttpMethod.PUT, "/api/v1/me", token, Map.of("name", "Asha"), null);
    assertThat(saved.getStatusCode()).as("used to be a 500 from the contact check").isEqualTo(HttpStatus.OK);
    assertThat(saved.getBody()).containsEntry("name", "Asha").containsEntry("mobile", mobile);
  }
}
