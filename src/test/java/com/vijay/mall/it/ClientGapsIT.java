package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

/** Gaps the Flutter team reported: CORS headers, conditional GET on catalog lists, uploads, push tokens, address phone, blocked customers, lenient dates. */
@SuppressWarnings({"rawtypes", "unchecked"})
class ClientGapsIT extends IntegrationTestBase {
  private static final String ORIGIN = "http://localhost:3000";

  @DynamicPropertySource
  static void cors(DynamicPropertyRegistry r) { r.add("app.cors.allowed-origin-patterns", () -> "http://localhost:*"); }

  private ResponseEntity<String> raw(HttpMethod m, String url, String token, Map<String, String> headers) {
    return client().method(m).uri(url).headers(h -> {
      if (token != null) h.setBearerAuth(token);
      headers.forEach(h::set);
    }).retrieve().toEntity(String.class);
  }

  // ---------- B00-02 CORS ----------

  @Test
  void preflightAllowsIfNoneMatchAndTheAnswerExposesEtagRetryAfterAndTraceId() {
    var pre = raw(HttpMethod.OPTIONS, "/api/v1/shop-settings", null, Map.of("Origin", ORIGIN,
        "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "if-none-match,idempotency-key,authorization"));
    assertThat(pre.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(pre.getHeaders().getFirst("Access-Control-Allow-Headers").toLowerCase()).contains("if-none-match", "idempotency-key");

    var res = raw(HttpMethod.GET, "/api/v1/shop-settings", null, Map.of("Origin", ORIGIN));
    String exposed = res.getHeaders().getFirst("Access-Control-Expose-Headers");
    assertThat(exposed).contains("ETag", "Retry-After", "X-Trace-Id");
  }

  // ---------- B10-14 ETag on catalog lists ----------

  @Test
  void catalogListsAnswer304OnARepeatAndAChangedEtagAfterAnEdit() {
    String admin = adminToken();
    for (String url : List.of("/api/v1/categories", "/api/v1/product-groups", "/api/v1/units", "/api/v1/hot-sale")) {
      var first = raw(HttpMethod.GET, url, null, Map.of());
      assertThat(first.getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
      String etag = first.getHeaders().getETag();
      assertThat(etag).as(url).isNotBlank();
      assertThat(first.getHeaders().getCacheControl()).as(url).isEqualTo("no-cache");
      var again = raw(HttpMethod.GET, url, null, Map.of("If-None-Match", etag));
      assertThat(again.getStatusCode()).as(url).isEqualTo(HttpStatus.NOT_MODIFIED);
      assertThat(again.getBody()).as(url).isNull();
    }

    String before = raw(HttpMethod.GET, "/api/v1/categories", null, Map.of()).getHeaders().getETag();
    String name = "Etag Test " + System.nanoTime();
    assertThat(post("/api/v1/admin/categories", admin, Map.of("nameEn", name, "nameGu", name)).getStatusCode().is2xxSuccessful()).isTrue();
    var after = raw(HttpMethod.GET, "/api/v1/categories", null, Map.of("If-None-Match", before));
    assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(after.getHeaders().getETag()).isNotEqualTo(before);
    assertThat(after.getBody()).contains(name);
  }

  @Test
  void otherEndpointsGetNoEtagFromTheCatalogFilter() {
    assertThat(raw(HttpMethod.GET, "/api/v1/products", null, Map.of()).getHeaders().getETag()).isNull();
  }

  // ---------- B00-04 uploads ----------

  private ResponseEntity<String> upload(String admin, byte[] bytes) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return "x.png"; } });
    return client().post().uri("/api/v1/admin/shop-profile/logo").headers(h -> h.setBearerAuth(admin))
        .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().toEntity(String.class);
  }

  @Test
  void aNonImageAndAnOversizeUploadNameTheFileField() {
    String admin = adminToken();
    var notImage = upload(admin, "hello".getBytes());
    assertThat(notImage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(notImage.getBody()).contains("\"code\":\"UNSUPPORTED_IMAGE\"").contains("\"file\":\"must be an image (JPEG, PNG or WebP)\"");

    var big = upload(admin, new byte[5 * 1024 * 1024 + 200_000]);
    assertThat(big.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(big.getBody()).contains("\"code\":\"FILE_TOO_LARGE\"").contains("\"file\":\"must be 5 MB or smaller\"");
  }

  // ---------- B00-01 / B10-03 push tokens ----------

  private int devices(String token) { return jdbc.queryForObject("select count(*) from device_token where fcm_token = ?", Integer.class, token); }

  private HttpStatusCode unregister(String token, String fcm) {
    return client().delete().uri(b -> b.path("/api/v1/me/devices/{t}").build(fcm)).headers(h -> { if (token != null) h.setBearerAuth(token); })
        .retrieve().toBodilessEntity().getStatusCode();
  }

  @Test
  void deviceTokenCanBeUnregisteredByItsOwnerOnlyAndRepeatedly() {
    String a = token(freshEmail());
    String b = token(freshEmail());
    String fcm = "dXy:APA91b-" + UUID.randomUUID() + "_x y+z="; // characters that must travel URL-encoded
    assertThat(post("/api/v1/me/devices", a, Map.of("fcmToken", fcm, "platform", "android")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    assertThat(unregister(b, fcm)).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(devices(fcm)).as("someone else's token is untouched").isEqualTo(1);

    assertThat(unregister(a, fcm)).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(devices(fcm)).isZero();
    assertThat(unregister(a, fcm)).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(unregister(a, "unknown")).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(unregister(null, "x")).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void logoutCanUnregisterThePushTokenAndStillWorksWithoutOne() {
    var login = login(freshEmail());
    String access = (String) login.get("accessToken");
    String fcm = "fcm-" + UUID.randomUUID();
    post("/api/v1/me/devices", access, Map.of("fcmToken", fcm, "platform", "android"));
    assertThat(devices(fcm)).isEqualTo(1);

    assertThat(post("/api/v1/auth/logout", null, Map.of("refreshToken", login.get("refreshToken"), "fcmToken", fcm)).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(devices(fcm)).isZero();

    var other = login(freshEmail());
    assertThat(post("/api/v1/auth/logout", null, Map.of("refreshToken", other.get("refreshToken"))).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(post("/api/v1/auth/logout", null, Map.of("refreshToken", "nope", "fcmToken", fcm)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  // ---------- B10-05 address phone, B10-04 name ----------

  private Map<String, Object> address(String phone) {
    Map<String, Object> m = new HashMap<>(Map.of("label", "Home", "line1", "1 Road", "area", "A", "pincode", "390001"));
    if (phone != null) m.put("phone", phone);
    return m;
  }

  @Test
  void addressPhoneIsValidatedStoredAndCopiedIntoTheOrderSnapshot() {
    String t = token(freshEmail());
    var bad = post("/api/v1/me/addresses", t, address("12345"));
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(((Map) ((Map) bad.getBody().get("details")).get("fields"))).containsEntry("phone", "must be 10 digits");

    var created = post("/api/v1/me/addresses", t, address("9876543210"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).containsEntry("phone", "9876543210");
    String id = (String) created.getBody().get("id");
    assertThat(getList("/api/v1/me/addresses", t).get(0)).containsEntry("phone", "9876543210");
    assertThat(post("/api/v1/me/addresses", t, address("")).getBody()).containsEntry("phone", null);
    assertThat(post("/api/v1/me/addresses", t, address(null)).getBody()).containsEntry("phone", null);

    restock("Potato Chips", 20);
    Object pid = productByName(t, "Potato Chips").get("id");
    Map<String, Object> cart = Map.of("items", List.of(Map.of("productId", pid, "qty", 1)), "coinMode", "NONE", "fulfilment", "DELIVERY");
    Map<String, Object> order = new HashMap<>(cart);
    order.put("paymentMethod", "COD");
    order.put("addressId", id);
    order.put("expectedTotalPaise", ((Number) post("/api/v1/cart/quote", t, cart).getBody().get("totalPaise")).longValue());
    var placed = call(HttpMethod.POST, "/api/v1/orders", t, order, UUID.randomUUID().toString());
    assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    var edited = address("9123456789");
    assertThat(call(HttpMethod.PUT, "/api/v1/me/addresses/" + id, t, edited, null).getBody()).containsEntry("phone", "9123456789");
    var snap = (Map) get("/api/v1/orders/" + placed.getBody().get("id"), t).getBody().get("address");
    assertThat(snap).containsEntry("phone", "9876543210");
  }

  @Test
  void aBlankProfileNameIsRefusedButAMissingNameKeepsTheSavedOne() {
    String t = token(freshEmail());
    assertThat(call(HttpMethod.PUT, "/api/v1/me", t, Map.of("name", "Asha"), null).getBody()).containsEntry("name", "Asha");
    var blank = call(HttpMethod.PUT, "/api/v1/me", t, Map.of("name", "   "), null);
    assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(((Map) ((Map) blank.getBody().get("details")).get("fields"))).containsEntry("name", "must not be blank");
    assertThat(call(HttpMethod.PUT, "/api/v1/me", t, Map.of("mobile", ""), null).getBody()).containsEntry("name", "Asha");
  }

  // ---------- B10-09 OTP messages ----------

  @Test
  void aWrongCodeSaysSoInPlainWords() {
    String email = freshEmail();
    post("/api/v1/auth/otp/send", null, Map.of("email", email));
    var wrong = post("/api/v1/auth/otp/verify", null, Map.of("email", email, "otp", "00000", "deviceId", "d"));
    assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(wrong.getBody()).containsEntry("code", "OTP_INVALID").containsEntry("message", "That code is not right. Check it and try again.");
    var none = post("/api/v1/auth/otp/verify", null, Map.of("email", freshEmail(), "otp", "12345", "deviceId", "d"));
    assertThat(none.getBody()).containsEntry("message", "That code has expired. Request a new one.");
  }

  // ---------- B10-15 blocked customers ----------

  @Test
  void aBlockedCustomerIsRefusedOnTheNextCallEvenWithAValidToken() {
    String admin = adminToken();
    String t = token(freshEmail());
    assertThat(get("/api/v1/me", t).getStatusCode()).isEqualTo(HttpStatus.OK); // now cached as "not blocked"
    String id = (String) get("/api/v1/me", t).getBody().get("id");
    assertThat(call(HttpMethod.PATCH, "/api/v1/admin/customers/" + id, admin, Map.of("active", false), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/me", t).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(call(HttpMethod.PATCH, "/api/v1/admin/customers/" + id, admin, Map.of("active", true), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/me", t).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void aCustomerBlockedBehindTheCachesBackIsStillRefusedOnTheFirstCall() {
    String email = freshEmail();
    String t = token(email);
    jdbc.update("update users set status = 'BLOCKED' where email = ?", email);
    assertThat(get("/api/v1/me", t).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // ---------- B10-12 lenient dates ----------

  private Map<String, Object> coupon(Object startAt) {
    Map<String, Object> m = new HashMap<>(Map.of("code", "D" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "kind", "FLAT", "value", 500));
    m.put("startAt", startAt);
    return m;
  }

  @Test
  void couponDatesAcceptOffsetZonelessAndPlainDateFormsAndRejectGarbage() {
    String admin = adminToken();
    var z = post("/api/v1/admin/coupons", admin, coupon("2026-11-09T10:00:00Z"));
    assertThat(z.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(z.getBody().get("startAt").toString()).startsWith("2026-11-09T10:00:00");

    var india = post("/api/v1/admin/coupons", admin, coupon("2026-11-09T10:00:00"));
    assertThat(india.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(india.getBody().get("startAt").toString()).startsWith("2026-11-09T04:30:00").endsWith("Z");

    var day = post("/api/v1/admin/coupons", admin, coupon("2026-11-09"));
    assertThat(day.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(day.getBody().get("startAt").toString()).startsWith("2026-11-08T18:30:00").endsWith("Z");

    var bad = post("/api/v1/admin/coupons", admin, coupon("next tuesday"));
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(bad.getBody()).containsEntry("code", "VALIDATION_FAILED");
  }
}
