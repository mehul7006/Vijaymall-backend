package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The endpoints and fields the Flutter app asked for (BACKEND_INTEGRATION_GAPS.md), end to end. */
@SuppressWarnings({"rawtypes", "unchecked"})
class FrontendGapsIT extends IntegrationTestBase {

  private String mobile() { return "98" + String.format("%08d", (long) (Math.random() * 100_000_000L)); }

  private Map<String, Object> mobileLogin(String mobile) {
    assertThat(post("/api/v1/auth/otp/send-mobile", null, Map.of("mobile", mobile)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    var res = post("/api/v1/auth/otp/verify-mobile", null,
        Map.of("mobile", mobile, "otp", "12345", "deviceId", UUID.randomUUID().toString()));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    return res.getBody();
  }

  // ---------- 2.1 mobile login ----------
  @Test
  void mobileLoginCreatesTheCustomerOnceAndReturnsTheSameAccountNextTime() {
    String m = mobile();
    Map<String, Object> first = mobileLogin(m);
    Map user = (Map) first.get("user");
    assertThat(user.get("mobile")).isEqualTo(m);
    assertThat(user.get("email")).isNull();
    assertThat(user.get("role")).isEqualTo("CUSTOMER");

    Map again = (Map) mobileLogin("+91 " + m).get("user");
    assertThat(again.get("id")).isEqualTo(user.get("id"));
    assertThat(get("/api/v1/me", (String) first.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void badNumberOrWrongCodeIsRejected() {
    assertThat(post("/api/v1/auth/otp/send-mobile", null, Map.of("mobile", "12345")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    String m = mobile();
    post("/api/v1/auth/otp/send-mobile", null, Map.of("mobile", m));
    var res = post("/api/v1/auth/otp/verify-mobile", null, Map.of("mobile", m, "otp", "00000"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(res.getBody().get("code")).isEqualTo("OTP_INVALID");
  }

  @Test
  void aVerifiedMobileCannotBeSwappedFromTheProfile() {
    String token = (String) mobileLogin(mobile()).get("accessToken");
    var res = call(HttpMethod.PUT, "/api/v1/me", token, Map.of("name", "Asha", "mobile", "9000000099"), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code")).isEqualTo("MOBILE_VERIFIED");
  }

  // ---------- 2.12 CORS and public catalog ----------
  @Test
  void browserPreflightFromALocalPortIsAllowedAndAStrangerIsNot() {
    var ok = client().options().uri("/api/v1/orders").header("Origin", "http://localhost:5555")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key").retrieve().toBodilessEntity();
    assertThat(ok.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(ok.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5555");
    assertThat(ok.getHeaders().getFirst("Access-Control-Allow-Headers").toLowerCase()).contains("idempotency-key");

    var bad = client().options().uri("/api/v1/orders").header("Origin", "https://evil.example")
        .header("Access-Control-Request-Method", "POST").retrieve().toBodilessEntity();
    assertThat(bad.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
  }

  @Test
  void guestsCanBrowseButNotWriteReviews() {
    var products = get("/api/v1/products?size=5", null);
    assertThat(products.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map first = ((List<Map>) products.getBody().get("items")).get(0);
    assertThat(first).containsKeys("productGroupId", "rating", "reviewCount");
    assertThat(get("/api/v1/hot-sale", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/products/" + first.get("id") + "/reviews", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(post("/api/v1/products/" + first.get("id") + "/reviews", null, Map.of("rating", 5)).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // ---------- 2.2 product groups ----------
  @Test
  void packsShareAGroupWhenTheAdminSaysSo() {
    String admin = adminToken();
    Map base = productByName(admin, "Potato Chips");
    Map body = new HashMap(Map.of("categoryId", base.get("categoryId"), "nameEn", "Potato Chips Big", "nameGu", "ચિપ્સ",
        "mrpPaise", 5000, "pricePaise", 4000, "packSize", "200 g", "qtyOnHand", 5, "productGroupId", base.get("productGroupId")));
    var res = post("/api/v1/admin/products", admin, body);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(res.getBody().get("productGroupId")).isEqualTo(base.get("productGroupId"));
    assertThat(base.get("productGroupId")).isNotNull();
  }

  // ---------- 2.11 reviews ----------
  @Test
  void aReviewUpdatesTheRatingAndWritingAgainReplacesIt() {
    String token = token(freshEmail());
    Object pid = productByName(token, "Digestive Biscuits").get("id");
    assertThat(post("/api/v1/products/" + pid + "/reviews", token, Map.of("rating", 5, "comment", "Crunchy")).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    var second = post("/api/v1/products/" + pid + "/reviews", token, Map.of("rating", 3));
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

    var list = get("/api/v1/products/" + pid + "/reviews", null).getBody();
    List<Map> mine = ((List<Map>) list.get("items")).stream().filter(r -> "Customer".equals(r.get("reviewer"))).toList();
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).get("rating")).isEqualTo(3);
    assertThat(((Number) list.get("reviewCount")).intValue()).isGreaterThanOrEqualTo(1);
    assertThat(((Number) get("/api/v1/products/" + pid, null).getBody().get("reviewCount")).intValue())
        .isEqualTo(((Number) list.get("reviewCount")).intValue());
    assertThat(post("/api/v1/products/" + pid + "/reviews", token, Map.of("rating", 9)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ---------- 2.10 wishlist ----------
  @Test
  void wishlistAddRemoveAndRepeat() {
    String token = token(freshEmail());
    Object pid = productByName(token, "Bath Soap").get("id");
    assertThat(post("/api/v1/me/wishlist/" + pid, token, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(post("/api/v1/me/wishlist/" + pid, token, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    List<Map<String, Object>> saved = getList("/api/v1/me/wishlist", token);
    assertThat(saved).hasSize(1);
    assertThat(saved.get(0).get("id")).isEqualTo(pid);

    assertThat(call(HttpMethod.DELETE, "/api/v1/me/wishlist/" + pid, token, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(call(HttpMethod.DELETE, "/api/v1/me/wishlist/" + pid, token, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(getList("/api/v1/me/wishlist", token)).isEmpty();
    assertThat(post("/api/v1/me/wishlist/" + UUID.randomUUID(), token, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ---------- 2.9 notifications ----------
  @Test
  void notificationsInboxStartsEmptyAndRejectsStrangersIds() {
    String token = token(freshEmail());
    var inbox = get("/api/v1/me/notifications", token);
    assertThat(inbox.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List) inbox.getBody().get("items")).isNotNull();
    assertThat(inbox.getBody()).containsKey("unread");
    assertThat(post("/api/v1/me/notifications/read-all", token, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(post("/api/v1/me/notifications/" + UUID.randomUUID() + "/read", token, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ---------- 2.6 stock history and 2.7 reports ----------
  @Test
  void stockHistoryShowsARestockWithNameAndPack() {
    String admin = adminToken();
    Map p = productByName(admin, "Orange Juice");
    post("/api/v1/admin/products/" + p.get("id") + "/stock", admin, Map.of("delta", 3, "reason", "RESTOCK"));
    var res = get("/api/v1/admin/stock-movements?productId=" + p.get("id") + "&size=5", admin);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map row = ((List<Map>) res.getBody().get("items")).get(0);
    assertThat(row.get("delta")).isEqualTo(3);
    assertThat(row.get("reason")).isEqualTo("RESTOCK");
    assertThat(row.get("name")).isEqualTo("Orange Juice");
    assertThat(row).containsKeys("packLabel", "at", "cashierId");
    assertThat(get("/api/v1/admin/stock-movements", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void reportSummaryCoversTheRequestedPeriod() {
    String admin = adminToken();
    var week = get("/api/v1/admin/reports/summary", admin);
    assertThat(week.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List) week.getBody().get("byDay")).hasSize(7);
    assertThat(week.getBody()).containsKeys("salesPaise", "orders", "coinsIssued", "topProducts");
    assertThat((List) get("/api/v1/admin/reports/summary?period=month", admin).getBody().get("byDay")).hasSize(30);
    assertThat((List) get("/api/v1/admin/reports/summary?from=2026-01-01&to=2026-01-10", admin).getBody().get("byDay")).hasSize(10);
    assertThat(get("/api/v1/admin/reports/summary?period=year", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/reports/summary", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ---------- 2.3 2.4 2.8 order details ----------
  private Map placeOrder(String token, Object pid, Map<String, Object> extra, String coupon) {
    var items = List.of(Map.of("productId", pid, "qty", 1));
    Map<String, Object> cart = new HashMap<>(Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP"));
    if (coupon != null) cart.put("couponCode", coupon);
    long total = ((Number) post("/api/v1/cart/quote", token, cart).getBody().get("totalPaise")).longValue();
    Map<String, Object> body = new HashMap<>(cart);
    body.putAll(Map.of("paymentMethod", "COD", "expectedTotalPaise", total));
    body.putAll(extra);
    var res = call(HttpMethod.POST, "/api/v1/orders", token, body, UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  @Test
  void ordersKeepTheSlotTheLineSnapshotAndTheCancelReason() {
    restock("Potato Chips", 5);
    String token = token(freshEmail());
    Object pid = productByName(token, "Potato Chips").get("id");
    Map order = placeOrder(token, pid, Map.of("slot", "6 PM - 8 PM"), null);
    assertThat(order.get("slot")).isEqualTo("6 PM - 8 PM");
    Map line = ((List<Map>) order.get("items")).get(0);
    assertThat(line.get("nameGu")).isNotNull();
    assertThat((String) line.get("unitLabel")).isNotNull();
    assertThat(line.get("unitPricePaise")).isNotNull();

    var cancelled = post("/api/v1/orders/" + order.get("id") + "/cancel", token, Map.of("reason", "Changed my mind"));
    assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(cancelled.getBody().get("cancelReason")).isEqualTo("Changed my mind");
    assertThat(get("/api/v1/orders/" + order.get("id"), token).getBody().get("cancelReason")).isEqualTo("Changed my mind");
  }

  @Test
  void cancelWorksWithoutABodyAndATooLongSlotIsRejected() {
    restock("Bath Soap", 5);
    String token = token(freshEmail());
    Object pid = productByName(token, "Bath Soap").get("id");
    Map order = placeOrder(token, pid, Map.of(), null);
    var cancelled = post("/api/v1/orders/" + order.get("id") + "/cancel", token, null);
    assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(cancelled.getBody().get("cancelReason")).isNull();

    var items = List.of(Map.of("productId", pid, "qty", 1));
    var tooLong = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", items, "paymentMethod", "COD",
        "fulfilment", "PICKUP", "expectedTotalPaise", 1, "slot", "x".repeat(41)), UUID.randomUUID().toString());
    assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ---------- 2.5 coupon flags ----------
  @Test
  void firstOrderOnlyCouponStopsWorkingAfterTheFirstOrder() {
    restock("Digestive Biscuits", 5);
    String admin = adminToken();
    String code = "FO" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    var made = post("/api/v1/admin/coupons", admin, Map.of("code", code, "kind", "FLAT", "value", 100,
        "firstOrderOnly", true, "worksWithCoins", false));
    assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(made.getBody().get("firstOrderOnly")).isEqualTo(true);
    assertThat(made.getBody().get("worksWithCoins")).isEqualTo(false);

    String token = token(freshEmail());
    Object pid = productByName(token, "Digestive Biscuits").get("id");
    placeOrder(token, pid, Map.of(), code);

    var again = post("/api/v1/cart/quote", token, Map.of("items", List.of(Map.of("productId", pid, "qty", 1)),
        "couponCode", code, "coinMode", "NONE", "fulfilment", "PICKUP"));
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(((Map) again.getBody().get("details")).get("reason")).isEqualTo("FIRST_ORDER_ONLY");
  }

  // ---------- round 2: staff active flag, public shop settings, OpenAPI names ----------
  @Test
  void staffListShowsWhoIsSwitchedOff() {
    String admin = adminToken();
    String email = freshEmail();
    var made = post("/api/v1/admin/staff", admin, Map.of("email", email, "name", "Day shift"));
    assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(made.getBody().get("active")).isEqualTo(true);
    Object id = made.getBody().get("id");

    assertThat(getList("/api/v1/admin/staff", admin)).anyMatch(u -> id.equals(u.get("id")) && Boolean.TRUE.equals(u.get("active")));
    call(HttpMethod.PATCH, "/api/v1/admin/staff/" + id, admin, Map.of("active", false), null);
    assertThat(getList("/api/v1/admin/staff", admin)).anyMatch(u -> id.equals(u.get("id")) && Boolean.FALSE.equals(u.get("active")));
  }

  @Test
  void guestsSeeTheShopCardButNotTheBillingDetails() {
    String admin = adminToken();
    Map current = get("/api/v1/admin/shop-settings", admin).getBody();
    Map<String, Object> form = new HashMap<>(current);
    form.put("promise", "Free home delivery on every order");
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, form, null).getStatusCode()).isEqualTo(HttpStatus.OK);

    var pub = get("/api/v1/shop-settings", null);
    assertThat(pub.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(pub.getBody().keySet()).containsExactlyInAnyOrder("shopName", "phone", "address", "promise");
    assertThat(pub.getBody().get("promise")).isEqualTo("Free home delivery on every order");

    // saving the form again without a promise keeps the one that was set
    form.remove("promise");
    call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, form, null);
    assertThat(get("/api/v1/shop-settings", null).getBody().get("promise")).isEqualTo("Free home delivery on every order");
  }

  @Test
  void openApiDescribesOrderLinesAndTheSalesSummaryWithTheirOwnSchemas() {
    Map schemas = (Map) ((Map) get("/v3/api-docs", null).getBody().get("components")).get("schemas");
    assertThat(((Map) ((Map) schemas.get("OrderItem")).get("properties")).keySet())
        .contains("productId", "name", "nameGu", "unitLabel", "qty", "unitPricePaise", "lineTotalPaise");
    assertThat(((Map) ((Map) schemas.get("PosItem")).get("properties")).keySet()).containsExactlyInAnyOrder("productId", "qty");
    assertThat(((Map) ((Map) schemas.get("SalesSummary")).get("properties")).keySet())
        .contains("salesPaise", "orders", "byDay", "topProducts", "coinsIssued");
    assertThat(schemas).containsKeys("OrderSummary", "QuoteLine", "WalletRule", "CoinRuleView");
  }
}

