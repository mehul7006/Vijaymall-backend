package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The admin configuration screen, the cache screen and the shop profile, against a real database. */
@SuppressWarnings({"unchecked", "rawtypes"})
class AdminConfigIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  private ResponseEntityHolder save(String token, Map... changes) {
    var res = call(HttpMethod.PUT, "/api/v1/admin/config", token, Map.of("changes", List.of(changes)), null);
    return new ResponseEntityHolder(res.getStatusCode(), res.getBody());
  }

  private record ResponseEntityHolder(Object status, Map body) {}

  private static Map change(String key, String value) { return Map.of("key", key, "value", value); }

  private Map row(Map view, String key) {
    for (Map g : (List<Map>) view.get("groups")) for (Map r : (List<Map>) g.get("items")) if (key.equals(r.get("key"))) return r;
    throw new AssertionError("no setting " + key);
  }

  @AfterEach
  void restore() {
    save(adminToken(), change("razorpay.keyId", ""), change("razorpay.keySecret", ""), change("gst.roundOff", ""), change("pos.cashierDiscountPercent", ""),
        change("log.level.app", ""));
    setPaymentMethod(adminToken(), "ONLINE", false);
  }

  @Test
  void onlyTheAdminSeesOrChangesConfiguration() {
    assertThat(get("/api/v1/admin/config", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/v1/admin/config", token(CASHIER)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/config", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(save(token(CASHIER), change("gst.roundOff", "NEAREST_RUPEE")).status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/config/bootstrap", token(CASHIER)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/cache", token(CASHIER)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void theScreenListsEveryGroupAndTheBootstrapItemsWithoutValues() {
    var view = get("/api/v1/admin/config", adminToken());
    assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map> groups = (List<Map>) view.getBody().get("groups");
    assertThat(groups).extracting(g -> g.get("group")).contains("PAYMENT", "PUSH", "SMS", "EMAIL", "LOGGING", "CACHE", "GST");
    assertThat(row(view.getBody(), "razorpay.keySecret")).containsEntry("secret", true).containsEntry("set", false);
    var boot = getList("/api/v1/admin/config/bootstrap", adminToken());
    assertThat(boot).isNotEmpty();
    assertThat(boot.toString()).doesNotContain("dev-pepper");
  }

  @Test
  void razorpayKeysSwitchOnlinePaymentOnWithoutARestartAndTheSecretIsNeverReturned() {
    String admin = adminToken();
    String cust = token(freshEmail());
    var res = save(admin, change("razorpay.keyId", "rzp_test_abc123"), change("razorpay.keySecret", "super-secret-value-9"));
    assertThat(res.status()).isEqualTo(HttpStatus.OK);
    assertThat(res.body().toString()).doesNotContain("super-secret-value-9");
    assertThat(row(res.body(), "razorpay.keySecret")).containsEntry("set", true).containsEntry("value", null);
    assertThat(row(res.body(), "razorpay.keyId")).containsEntry("value", "rzp_test_abc123");
    assertThat(jdbc.queryForObject("select value from app_setting where name = 'razorpay.keySecret'", String.class)).doesNotContain("super-secret-value-9");
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity = 'app_setting'", Integer.class)).isGreaterThanOrEqualTo(2);

    setPaymentMethod(admin, "ONLINE", true);
    assertThat(getList("/api/v1/payment-methods", cust)).extracting(m -> m.get("code")).contains("ONLINE");

    // a secret that is not listed stays as it is
    var other = save(admin, change("gst.roundOff", "NEAREST_RUPEE"));
    assertThat(row(other.body(), "razorpay.keySecret")).containsEntry("set", true);

    // clearing it puts the shop back to "not configured"
    save(admin, change("razorpay.keySecret", ""));
    assertThat(row(get("/api/v1/admin/config", admin).getBody(), "razorpay.keySecret")).containsEntry("set", false);
  }

  @Test
  void badValuesAreRefusedAndNothingInTheBatchIsSaved() {
    String admin = adminToken();
    var res = save(admin, change("gst.roundOff", "NEAREST_RUPEE"), change("pos.cashierDiscountPercent", "250"));
    assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(row(get("/api/v1/admin/config", admin).getBody(), "gst.roundOff").get("value")).isNotEqualTo("NEAREST_RUPEE");
    assertThat(save(admin, change("no.such.setting", "x")).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(save(admin, change("log.level.app", "LOUD")).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of()), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void logDetailCanBeChangedLive() {
    String admin = adminToken();
    assertThat(save(admin, change("log.level.app", "DEBUG")).status()).isEqualTo(HttpStatus.OK);
    var st = get("/api/v1/admin/logs/status", admin);
    assertThat(st.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(st.getBody().toString()).contains("DEBUG");
  }

  @Test
  void connectionTestsReportMissingKeysInsteadOfFailing() {
    String admin = adminToken();
    var rz = call(HttpMethod.POST, "/api/v1/admin/config/test/razorpay", admin, Map.of(), null);
    assertThat(rz.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(rz.getBody()).containsEntry("ok", false);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/config/test/nothing", admin, Map.of(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/config/test/razorpay", token(CASHIER), Map.of(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ---------- caches ----------
  @Test
  void adminSeesCacheStatsAndCanHardRefresh() {
    String admin = adminToken();
    get("/api/v1/categories", token(freshEmail()));
    var stats = getList("/api/v1/admin/cache", admin);
    assertThat(stats).extracting(s -> s.get("name")).contains("categories", "shop", "shop-profile", "units", "dashboard");
    var all = call(HttpMethod.POST, "/api/v1/admin/cache/refresh", admin, null, null);
    assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/cache/refresh/categories", admin, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/cache/refresh/settings", admin, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/cache/refresh/nope", admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/cache/refresh", token(CASHIER), null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'CACHE_REFRESH'", Integer.class)).isGreaterThanOrEqualTo(3);
  }

  @Test
  void aNewCategoryIsVisibleAtOnceEvenThoughCategoriesAreCached() {
    String admin = adminToken();
    String cust = token(freshEmail());
    get("/api/v1/categories", cust); // warm the cache
    String name = "Cache Test " + System.nanoTime();
    var created = call(HttpMethod.POST, "/api/v1/admin/categories", admin, Map.of("nameEn", name, "nameGu", name), null);
    assertThat(created.getStatusCode().is2xxSuccessful()).as(String.valueOf(created.getBody())).isTrue();
    assertThat(getList("/api/v1/categories", cust)).extracting(c -> c.get("nameEn")).contains(name);
  }
}
