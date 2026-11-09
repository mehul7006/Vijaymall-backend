package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.OtpRepository;
import com.vijay.mall.identity.infra.RefreshTokenRepository;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@SuppressWarnings({"rawtypes", "unchecked"})
class IdentityCoverageIT extends IntegrationTestBase {
  @Autowired UserRepository users;
  @Autowired RefreshTokenRepository refreshTokens;
  @Autowired OtpRepository otps;

  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");

  private String freshMobile() {
    return "9" + String.format("%09d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000L);
  }

  private Map<String, Object> row(UUID id) { return jdbc.queryForMap("select * from users where id = ?", id); }

  // ---- DELETE /me ----

  @Test
  void aCustomerCanDeleteTheirAccountAndOnlyAnonymisedHusksRemain() {
    String email = freshEmail();
    Map<String, Object> auth = login(email);
    String token = (String) auth.get("accessToken");
    UUID id = UUID.fromString((String) ((Map) auth.get("user")).get("id"));

    assertThat(post("/api/v1/me/addresses", token, Map.of("label", "Home", "line1", "1 Main", "area", "Centre", "pincode", "380001"))
        .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(post("/api/v1/me/devices", token, Map.of("fcmToken", "fcm-" + UUID.randomUUID(), "platform", "android"))
        .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(jdbc.queryForObject("select count(*) from address where user_id = ?", Integer.class, id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from device_token where user_id = ?", Integer.class, id)).isEqualTo(1);

    var deleted = call(HttpMethod.DELETE, "/api/v1/me", token, null, null);
    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    assertThat(jdbc.queryForObject("select count(*) from address where user_id = ?", Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from device_token where user_id = ?", Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id = ? and revoked_at is null", Integer.class, id)).isZero();
    Map<String, Object> u = row(id);
    assertThat(u.get("email")).isEqualTo("deleted-" + id + "@deleted.invalid");
    assertThat(u.get("name")).isNull();
    assertThat(u.get("mobile")).isNull();
    assertThat(u.get("status")).isEqualTo("BLOCKED");

    // the access token used to delete is refused at once, for every kind of call
    assertThat(get("/api/v1/me", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(call(HttpMethod.PUT, "/api/v1/me", token, Map.of("name", "X"), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(post("/api/v1/me/addresses", token, Map.of("label", "Home", "line1", "1 Main", "area", "Centre", "pincode", "380001"))
        .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    // the same email can no longer sign in to that account
    assertThat(users.findByEmail(email)).isEmpty();
  }

  @Test
  void staffCannotDeleteTheirAccountFromTheCustomerApp() {
    var res = call(HttpMethod.DELETE, "/api/v1/me", adminToken(), null, null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ---- blocked staff lose access with a token that is still valid ----

  @Test
  void aBlockedCashierIsRefusedAtOnceWithTheirStillValidToken() {
    String email = freshEmail();
    login(email);
    jdbc.update("update users set role = 'CASHIER' where email = ?", email);
    String token = token(email);
    assertThat(get("/api/v1/me", token).getStatusCode()).isEqualTo(HttpStatus.OK);

    jdbc.update("update users set status = 'BLOCKED' where email = ?", email);
    var res = get("/api/v1/me", token);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // ---- UserRepository ----

  @Test
  void profileUpdateNeverBlanksOrSilentlyChangesAVerifiedNumber() {
    UUID id = users.insertMobileCustomer(freshMobile(), NOW).id();
    String verified = (String) row(id).get("mobile");

    // a name-only update keeps the verified number
    users.updateProfile(id, "Asha", null, NOW);
    assertThat(row(id).get("mobile")).isEqualTo(verified);
    assertThat(row(id).get("name")).isEqualTo("Asha");
    assertThat(row(id).get("mobile_verified")).isEqualTo(true);

    // sending the same number again keeps it verified
    users.updateProfile(id, "Asha", verified, NOW);
    assertThat(row(id).get("mobile_verified")).isEqualTo(true);

    // another number replaces it and is no longer verified
    String other = freshMobile();
    users.updateProfile(id, "Asha", other, NOW);
    assertThat(row(id).get("mobile")).isEqualTo(other);
    assertThat(row(id).get("mobile_verified")).isEqualTo(false);

    // an unverified account that also has an email may have its number blanked; unknown ids are ignored
    UUID withEmail = users.insert(freshEmail(), User.CUSTOMER, NOW).id();
    users.updateProfile(withEmail, "Bina", freshMobile(), NOW);
    users.updateProfile(withEmail, "Bina", null, NOW);
    assertThat(row(withEmail).get("mobile")).isNull();
    users.updateProfile(UUID.randomUUID(), "Nobody", null, NOW);
  }

  @Test
  void mobileLockedOnlyForAVerifiedNumberThatWouldChange() {
    String mobile = freshMobile();
    UUID verified = users.insertMobileCustomer(mobile, NOW).id();
    assertThat(users.mobileLocked(verified, null)).isFalse();
    assertThat(users.mobileLocked(verified, mobile)).isFalse();
    assertThat(users.mobileLocked(verified, freshMobile())).isTrue();
    assertThat(users.mobileLocked(UUID.randomUUID(), freshMobile())).isFalse();

    UUID unverified = users.insert(freshEmail(), User.CUSTOMER, NOW).id();
    users.updateProfile(unverified, null, freshMobile(), NOW);
    assertThat(users.mobileLocked(unverified, freshMobile())).isFalse();
  }

  @Test
  void upsertStaffCreatesPromotesAndKeepsExistingDetailsWhenBlank() {
    String email = freshEmail();
    String mobile = freshMobile();
    User created = users.upsertStaff(email, "Ravi", mobile, User.CASHIER, NOW);
    assertThat(created.role()).isEqualTo(User.CASHIER);
    assertThat(created.name()).isEqualTo("Ravi");
    assertThat(created.mobile()).isEqualTo(mobile);
    assertThat(users.byRole(User.CASHIER)).extracting(User::id).contains(created.id());
    assertThat(users.staffIds()).contains(created.id());
    assertThat(users.adminIds()).doesNotContain(created.id());

    // blank name and mobile leave what is stored; the role changes
    User promoted = users.upsertStaff(email, " ", "", User.ADMIN, NOW);
    assertThat(promoted.id()).isEqualTo(created.id());
    assertThat(promoted.role()).isEqualTo(User.ADMIN);
    assertThat(promoted.name()).isEqualTo("Ravi");
    assertThat(promoted.mobile()).isEqualTo(mobile);
    assertThat(users.adminIds()).contains(created.id());

    // null name and mobile behave the same
    User again = users.upsertStaff(email, null, null, User.CASHIER, NOW);
    assertThat(again.name()).isEqualTo("Ravi");
    assertThat(again.mobile()).isEqualTo(mobile);

    // new details replace the old ones
    User renamed = users.upsertStaff(email, "Ravi K", null, User.CASHIER, NOW);
    assertThat(renamed.name()).isEqualTo("Ravi K");
  }

  @Test
  void setRoleAndStatusChangeTheAccount() {
    UUID id = users.insert(freshEmail(), User.CUSTOMER, NOW).id();
    users.setRole(id, User.CASHIER);
    assertThat(users.findById(id).orElseThrow().role()).isEqualTo(User.CASHIER);
    assertThat(users.staffIds()).contains(id);
    users.setStatus(id, "BLOCKED");
    assertThat(users.isBlocked(id)).isTrue();
    assertThat(users.staffIds()).doesNotContain(id);
    users.setRole(UUID.randomUUID(), User.ADMIN); // unknown id: ignored
  }

  @Test
  void counterCustomersAreFoundAdoptedAndPreferTheVerifiedOwner() {
    String mobile = freshMobile();
    User counter = users.insertCounterCustomer(mobile, NOW);
    assertThat(users.findByMobile(mobile)).map(User::id).contains(counter.id());
    assertThat(users.findByVerifiedMobile(mobile)).isEmpty();

    assertThat(users.adoptCounterCustomer(mobile)).map(User::id).contains(counter.id());
    assertThat(users.findByVerifiedMobile(mobile)).map(User::id).contains(counter.id());
    assertThat(users.findByMobile(mobile)).map(User::id).contains(counter.id());
    assertThat(users.adoptCounterCustomer(freshMobile())).isEmpty();
    assertThat(users.findByMobile(freshMobile())).isEmpty();
  }

  @Test
  void anonymizeStripsPersonalDataAndBlocksTheAccount() {
    String mobile = freshMobile();
    UUID id = users.insertMobileCustomer(mobile, NOW).id();
    users.updateProfile(id, "Gone", mobile, NOW);
    users.anonymize(id, NOW);
    Map<String, Object> u = row(id);
    assertThat(u.get("email")).isEqualTo("deleted-" + id + "@deleted.invalid");
    assertThat(u.get("name")).isNull();
    assertThat(u.get("mobile")).isNull();
    assertThat(u.get("mobile_verified")).isEqualTo(false);
    assertThat(u.get("status")).isEqualTo("BLOCKED");
    users.anonymize(UUID.randomUUID(), NOW); // unknown id: ignored
  }

  // ---- refresh tokens and one-time codes ----

  @Test
  void refreshTokensAreRevokedPerDeviceWithOrWithoutADeviceIdAndAllAtOnce() {
    UUID user = users.insert(freshEmail(), User.CUSTOMER, NOW).id();
    Instant later = NOW.plusSeconds(3600);
    String none = UUID.randomUUID().toString(), d1 = UUID.randomUUID().toString(), d2 = UUID.randomUUID().toString();
    refreshTokens.insert(user, none, null, later, NOW);
    refreshTokens.insert(user, d1, "dev-1", later, NOW);
    refreshTokens.insert(user, d2, "dev-2", later, NOW);

    refreshTokens.revokeDevice(user, null, NOW);
    assertThat(refreshTokens.findByHash(none).orElseThrow().revoked()).isTrue();
    assertThat(refreshTokens.findByHash(d1).orElseThrow().revoked()).isFalse();

    refreshTokens.revokeDevice(user, "dev-1", NOW);
    assertThat(refreshTokens.findByHash(d1).orElseThrow().revoked()).isTrue();
    assertThat(refreshTokens.findByHash(d2).orElseThrow().revoked()).isFalse();

    refreshTokens.revokeAll(user, NOW);
    assertThat(refreshTokens.findByHash(d2).orElseThrow().revoked()).isTrue();
  }

  @Test
  void oldOneTimeCodesCanBeDeleted() {
    String email = freshEmail();
    Instant old = Instant.parse("2001-01-01T00:00:00Z");
    otps.insert(email, "hash", old.plusSeconds(300), old);
    assertThat(otps.latestOpen(email)).isPresent();
    otps.deleteOlderThan(old.plusSeconds(86_400));
    assertThat(otps.latestOpen(email)).isEmpty();
  }
}
