package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Profile, address book and device registration over HTTP, against the real database. */
class IdentityApiIT extends IntegrationTestBase {
  private static Map<String, Object> addr(String label, boolean def) {
    return Map.of("label", label, "line1", "1 Road", "area", "Area", "pincode", "390001", "isDefault", def);
  }

  @Test
  @SuppressWarnings("unchecked")
  void profileIsReadAndUpdated() {
    String email = freshEmail();
    String t = token(email);
    assertThat(get("/api/v1/me", t).getBody()).containsEntry("email", email).containsEntry("role", "CUSTOMER");

    var upd = call(HttpMethod.PUT, "/api/v1/me", t, Map.of("name", "  Asha  ", "mobile", "9876543210"), null);
    assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(upd.getBody()).containsEntry("name", "Asha").containsEntry("mobile", "9876543210");

    var cleared = call(HttpMethod.PUT, "/api/v1/me", t, Map.of("name", "Asha", "mobile", ""), null);
    assertThat(cleared.getBody().get("mobile")).isNull();
  }

  @Test
  void invalidMobileIsRejected() {
    var res = call(HttpMethod.PUT, "/api/v1/me", token(freshEmail()), Map.of("name", "A", "mobile", "123"), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void addressBookKeepsExactlyOneDefault() {
    String t = token(freshEmail());
    var first = post("/api/v1/me/addresses", t, addr("Home", false));
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.getBody().get("isDefault")).as("first address becomes default").isEqualTo(true);

    var second = post("/api/v1/me/addresses", t, addr("Work", false));
    assertThat(second.getBody().get("isDefault")).isEqualTo(false);

    var third = post("/api/v1/me/addresses", t, addr("Shop", true));
    assertThat(third.getBody().get("isDefault")).isEqualTo(true);

    List<Map<String, Object>> list = getList("/api/v1/me/addresses", t);
    assertThat(list).hasSize(3);
    assertThat(list.stream().filter(a -> Boolean.TRUE.equals(a.get("isDefault")))).singleElement()
        .satisfies(a -> assertThat(a.get("label")).isEqualTo("Shop"));

    // edit the second one into the default
    String id = (String) second.getBody().get("id");
    var edited = call(HttpMethod.PUT, "/api/v1/me/addresses/" + id, t, addr("Office", true), null);
    assertThat(edited.getBody()).containsEntry("label", "Office").containsEntry("isDefault", true);
    List<Map<String, Object>> after = getList("/api/v1/me/addresses", t);
    assertThat(after.stream().filter(a -> Boolean.TRUE.equals(a.get("isDefault")))).hasSize(1);

    // delete
    assertThat(call(HttpMethod.DELETE, "/api/v1/me/addresses/" + id, t, null, null).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(getList("/api/v1/me/addresses", t)).hasSize(2);
  }

  @Test
  void otherUsersCannotTouchMyAddress() {
    String owner = token(freshEmail());
    String other = token(freshEmail());
    String id = (String) post("/api/v1/me/addresses", owner, addr("Home", true)).getBody().get("id");
    assertThat(call(HttpMethod.PUT, "/api/v1/me/addresses/" + id, other, addr("Hacked", true), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    call(HttpMethod.DELETE, "/api/v1/me/addresses/" + id, other, null, null);
    assertThat(getList("/api/v1/me/addresses", owner)).hasSize(1);
  }

  @Test
  void addressNeedsItsRequiredFields() {
    var res = post("/api/v1/me/addresses", token(freshEmail()), Map.of("label", "", "line1", "x"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void deviceRegistrationIsIdempotentPerToken() {
    String t = token(freshEmail());
    String fcm = "fcm-" + UUID.randomUUID();
    var body = Map.of("fcmToken", fcm, "platform", "ANDROID");
    assertThat(post("/api/v1/me/devices", t, body).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(post("/api/v1/me/devices", t, body).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(post("/api/v1/me/devices", t, Map.of("fcmToken", "", "platform", "")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void blockedUserCannotSignIn() {
    // Block through the database the way an operator would, then try to log in again.
    String email = freshEmail();
    login(email);
    jdbc.update("UPDATE users SET status = 'BLOCKED' WHERE email = ?", email);
    post("/api/v1/auth/otp/send", null, Map.of("email", email));
    var res = post("/api/v1/auth/otp/verify", null, Map.of("email", email, "otp", "12345", "deviceId", "d"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
