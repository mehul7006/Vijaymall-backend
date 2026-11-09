package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Coupons end to end: admin management, validation at quote time, usage counting and restore on cancel. */
@SuppressWarnings({"rawtypes", "unchecked"})
class CouponApiIT extends IntegrationTestBase {

  private String code() { return "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }

  private Map<String, Object> coupon(String code, Map<String, Object> extra) {
    Map<String, Object> b = new HashMap<>(Map.of("code", code, "kind", "FLAT", "value", 500));
    b.putAll(extra);
    return b;
  }

  private String create(String admin, Map<String, Object> body) {
    var res = post("/api/v1/admin/coupons", admin, body);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  private Object juice(String token) { return productByName(token, "Orange Juice").get("id"); }

  private org.springframework.http.ResponseEntity<Map> quote(String token, Object pid, String code) {
    Map<String, Object> req = new HashMap<>(Map.of("items", List.of(Map.of("productId", pid, "qty", 1)),
        "coinMode", "NONE", "fulfilment", "PICKUP"));
    if (code != null) req.put("couponCode", code);
    return post("/api/v1/cart/quote", token, req);
  }

  private String reason(org.springframework.http.ResponseEntity<Map> res) {
    return (String) ((Map<?, ?>) res.getBody().get("details")).get("reason");
  }

  private org.springframework.http.ResponseEntity<Map> order(String token, Object pid, String code) {
    long total = ((Number) quote(token, pid, code).getBody().get("totalPaise")).longValue();
    Map<String, Object> body = new HashMap<>(Map.of("items", List.of(Map.of("productId", pid, "qty", 1)), "coinMode", "NONE",
        "fulfilment", "PICKUP", "paymentMethod", "COD", "expectedTotalPaise", total));
    if (code != null) body.put("couponCode", code);
    return call(HttpMethod.POST, "/api/v1/orders", token, body, UUID.randomUUID().toString());
  }

  @Test
  void adminCreatesListsUpdatesAndDeletes() {
    String admin = adminToken();
    String c = code();
    String id = create(admin, coupon(c.toLowerCase(), Map.of("perUserLimit", 2, "totalLimit", 10)));
    assertThat(getList("/api/v1/admin/coupons", admin)).anyMatch(x -> id.equals(x.get("id")) && c.equals(x.get("code")));

    var upd = call(HttpMethod.PUT, "/api/v1/admin/coupons/" + id, admin,
        coupon(c, Map.of("kind", "PERCENT", "value", 10, "maxDiscountPaise", 300)), null);
    assertThat(upd.getBody()).containsEntry("kind", "PERCENT").containsEntry("value", 10).containsEntry("maxDiscountPaise", 300);

    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/coupons/" + id, admin, null, null).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(getList("/api/v1/admin/coupons", admin)).noneMatch(x -> id.equals(x.get("id")));
  }

  @Test
  void adminInputIsValidated() {
    String admin = adminToken();
    String existing = code();
    create(admin, coupon(existing, Map.of()));
    assertThat(post("/api/v1/admin/coupons", admin, coupon(existing, Map.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/coupons", admin, coupon(code(), Map.of("kind", "BOGUS"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/coupons", admin, coupon(code(), Map.of("kind", "PERCENT", "value", 101))).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/coupons", admin, coupon(code(), Map.of("value", 0))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/coupons", admin, coupon(code(), Map.of("startAt", "2030-01-02T00:00:00Z", "endAt", "2030-01-01T00:00:00Z")))
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/coupons/" + UUID.randomUUID(), admin, coupon(code(), Map.of()), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/coupons/" + UUID.randomUUID(), admin, null, null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);

    String other = create(admin, coupon(code(), Map.of()));
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/coupons/" + other, admin, coupon(existing, Map.of()), null).getStatusCode())
        .as("renaming onto an existing code").isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void customersCannotManageCoupons() {
    assertThat(get("/api/v1/admin/coupons", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void validCouponReducesTheTotalAndIsCaseInsensitive() {
    String admin = adminToken();
    String c = code();
    create(admin, coupon(c, Map.of()));
    String t = token(freshEmail());
    Object pid = juice(t);
    long full = ((Number) quote(t, pid, null).getBody().get("totalPaise")).longValue();
    var q = quote(t, pid, " " + c.toLowerCase() + " ");
    assertThat(q.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(q.getBody()).containsEntry("couponCode", c).containsEntry("couponDiscountPaise", 500);
    assertThat(((Number) q.getBody().get("totalPaise")).longValue()).isEqualTo(full - 500);
  }

  @Test
  void invalidCouponsExplainWhy() {
    String admin = adminToken();
    String t = token(freshEmail());
    Object pid = juice(t);

    assertThat(reason(quote(t, pid, "NOPE" + code()))).isEqualTo("NOT_FOUND");

    String minOrder = code();
    create(admin, coupon(minOrder, Map.of("minOrderPaise", 10_000_000)));
    assertThat(reason(quote(t, pid, minOrder))).isEqualTo("MIN_ORDER");

    String expired = code();
    create(admin, coupon(expired, Map.of("startAt", Instant.now().minusSeconds(7200).toString(), "endAt", Instant.now().minusSeconds(3600).toString())));
    assertThat(reason(quote(t, pid, expired))).isEqualTo("EXPIRED");

    String future = code();
    create(admin, coupon(future, Map.of("startAt", Instant.now().plusSeconds(3600).toString())));
    assertThat(reason(quote(t, pid, future))).isEqualTo("EXPIRED");

    String off = code();
    create(admin, coupon(off, Map.of("active", false)));
    assertThat(reason(quote(t, pid, off))).isEqualTo("EXPIRED");

    String otherCategory = code();
    var unknown = post("/api/v1/admin/coupons", admin, coupon(otherCategory, Map.of("categoryId", UUID.randomUUID().toString())));
    assertThat(unknown.getStatusCode()).as("a category that does not exist is the caller's mistake, not a server error")
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void perUserLimitIsEnforcedAndCancelGivesTheUseBack() {
    String admin = adminToken();
    String c = code();
    create(admin, coupon(c, Map.of("perUserLimit", 1)));
    String t = token(freshEmail());
    Object pid = juice(t);

    var placed = order(t, pid, c);
    assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(placed.getBody()).containsEntry("couponCode", c);
    assertThat(reason(quote(t, pid, c))).isEqualTo("LIMIT_REACHED");
    assertThat(quote(token(freshEmail()), pid, c).getStatusCode()).as("another customer is unaffected").isEqualTo(HttpStatus.OK);

    post("/api/v1/orders/" + placed.getBody().get("id") + "/cancel", t, null);
    assertThat(quote(t, pid, c).getStatusCode()).as("use restored after cancel").isEqualTo(HttpStatus.OK);
  }

  @Test
  void totalLimitHoldsEvenWhenManyCustomersRace() throws Exception {
    String admin = adminToken();
    String c = code();
    create(admin, coupon(c, Map.of("totalLimit", 2)));
    List<String> tokens = IntStream.range(0, 6).mapToObj(_ -> token(freshEmail())).toList();
    Object pid = juice(tokens.get(0));
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Integer>> results = tokens.stream().map(t -> pool.submit(() -> order(t, pid, c).getStatusCode().value())).toList();
      long created = 0;
      for (var f : results) if (f.get() == 201) created++;
      assertThat(created).isEqualTo(2);
    }
  }

  @Test
  void usedCouponIsDeactivatedNotDeleted() {
    String admin = adminToken();
    String c = code();
    String id = create(admin, coupon(c, Map.of()));
    String t = token(freshEmail());
    assertThat(order(t, juice(t), c).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    call(HttpMethod.DELETE, "/api/v1/admin/coupons/" + id, admin, null, null);
    assertThat(getList("/api/v1/admin/coupons", admin)).anyMatch(x -> id.equals(x.get("id")) && Boolean.FALSE.equals(x.get("active")));
  }

  @Test
  void publicListShowsOnlyOffersACustomerCouldUseAndNeedsNoSignIn() {
    String admin = adminToken();
    String visible = code();
    String inactive = code();
    String ended = code();
    String notStarted = code();
    String usedUp = code();
    create(admin, coupon(visible, Map.of("minOrderPaise", 1000, "perUserLimit", 1, "totalLimit", 50)));
    create(admin, coupon(inactive, Map.of("active", false)));
    create(admin, coupon(ended, Map.of("startAt", Instant.now().minusSeconds(7200).toString(), "endAt", Instant.now().minusSeconds(3600).toString())));
    create(admin, coupon(notStarted, Map.of("startAt", Instant.now().plusSeconds(3600).toString())));
    create(admin, coupon(usedUp, Map.of("totalLimit", 1)));
    String t = token(freshEmail());
    assertThat(order(t, juice(t), usedUp).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    var res = getList("/api/v1/coupons", null);
    assertThat(res).anyMatch(x -> visible.equals(x.get("code")));
    assertThat(res).noneMatch(x -> List.of(inactive, ended, notStarted, usedUp).contains(x.get("code")));

    var shown = res.stream().filter(x -> visible.equals(x.get("code"))).findFirst().orElseThrow();
    assertThat(shown).containsEntry("kind", "FLAT").containsEntry("value", 500).containsEntry("minOrderPaise", 1000)
        .containsEntry("worksWithCoins", true).containsEntry("firstOrderOnly", false)
        .doesNotContainKeys("id", "usedCount", "totalLimit");
    assertThat(getList("/api/v1/coupons", t)).as("signed-in customers see the same list").anyMatch(x -> visible.equals(x.get("code")));
  }
}
