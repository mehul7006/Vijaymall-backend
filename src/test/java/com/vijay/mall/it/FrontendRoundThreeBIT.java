package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Third frontend review, orders / identity / staff / counter / credit / purchasing side: each finding is reproduced through the real
 * HTTP API against PostgreSQL and checked to be answered as the guide now says.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class FrontendRoundThreeBIT extends IntegrationTestBase {
  @BeforeEach
  void stock() {
    restock("Orange Juice", 40);
    restock("Potato Chips", 40);
    restock("Basmati Rice", 40);
  }

  private static Map fields(Map body) { return (Map) ((Map) body.get("details")).get("fields"); }

  private String uuid() { return UUID.randomUUID().toString(); }

  private String mobile() { return "9" + String.format("%09d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000L); }

  // ---------- staff ----------
  @Test
  void aStaffEmailThatAlreadyHasAnAccountIsNotSilentlyPromoted() {
    String admin = adminToken();
    String email = freshEmail();
    var made = post("/api/v1/admin/staff", admin, Map.of("email", email, "name", "Night", "mobile", "9876501234"));
    assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var again = post("/api/v1/admin/staff", admin, Map.of("email", email, "confirmPromote", true));
    assertThat(again.getStatusCode()).as("a cashier is never 'promoted' again").isEqualTo(HttpStatus.CONFLICT);
    assertThat(again.getBody().get("code")).isEqualTo("EMAIL_ALREADY_REGISTERED");

    String customerEmail = freshEmail();
    login(customerEmail);
    var refused = post("/api/v1/admin/staff", admin, Map.of("email", customerEmail, "name", "Overwritten"));
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(refused.getBody().get("code")).isEqualTo("EMAIL_ALREADY_REGISTERED");
    assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, customerEmail)).isEqualTo("CUSTOMER");
    assertThat(jdbc.queryForObject("select name from users where email = ?", String.class, customerEmail)).isNotEqualTo("Overwritten");

    var promoted = post("/api/v1/admin/staff", admin, Map.of("email", customerEmail, "confirmPromote", true));
    assertThat(promoted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, customerEmail)).isEqualTo("CASHIER");

    String blockedEmail = freshEmail();
    login(blockedEmail);
    jdbc.update("update users set status = 'BLOCKED' where email = ?", blockedEmail);
    var blocked = post("/api/v1/admin/staff", admin, Map.of("email", blockedEmail, "confirmPromote", true));
    assertThat(blocked.getStatusCode()).as("a blocked account is never promoted").isEqualTo(HttpStatus.CONFLICT);
    assertThat(jdbc.queryForObject("select status from users where email = ?", String.class, blockedEmail)).isEqualTo("BLOCKED");

    var adminTry = post("/api/v1/admin/staff", admin, Map.of("email", "admin@vijaysupermall.local", "confirmPromote", true));
    assertThat(adminTry.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void staffNameAndMobileAreChecked() {
    String admin = adminToken();
    var badMobile = post("/api/v1/admin/staff", admin, Map.of("email", freshEmail(), "mobile", "12345"));
    assertThat(badMobile.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(badMobile.getBody()).get("mobile")).isEqualTo("must be 10 digits");
    var longName = post("/api/v1/admin/staff", admin, Map.of("email", freshEmail(), "name", "n".repeat(81)));
    assertThat(longName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(longName.getBody())).containsKey("name");
    assertThat(post("/api/v1/admin/staff", admin, Map.of("email", freshEmail(), "name", "n".repeat(80))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  // ---------- identity ----------
  @Test
  void profileAndAddressSizesAreLimited() {
    String t = token(freshEmail());
    var name = call(HttpMethod.PUT, "/api/v1/me", t, Map.of("name", "n".repeat(81)), null);
    assertThat(name.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(name.getBody())).containsKey("name");
    var addr = post("/api/v1/me/addresses", t, Map.of("label", "l".repeat(31), "line1", "a".repeat(201), "area", "r".repeat(81), "pincode", "380001"));
    assertThat(addr.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(addr.getBody())).containsKeys("label", "line1", "area");
    assertThat(post("/api/v1/me/addresses", t, Map.of("label", "l".repeat(30), "line1", "a".repeat(200), "area", "r".repeat(80), "pincode", "380001"))
        .getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  // ---------- credit and counter bounds ----------
  @Test
  void moneyInputsHaveBoundsSoOverflowNeverReachesTheBusinessRules() {
    String admin = adminToken();
    var limit = call(HttpMethod.PUT, "/api/v1/admin/credit/limit", admin, Map.of("mobile", mobile(), "limitPaise", 50000), null);
    assertThat(limit.getStatusCode()).isEqualTo(HttpStatus.OK);
    Object id = limit.getBody().get("customerId");
    var huge = post("/api/v1/admin/credit/accounts/" + id + "/adjust", admin, Map.of("amountPaise", Long.MAX_VALUE, "note", "x"));
    assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(huge.getBody()).get("amountPaise")).isEqualTo("must be at most 100000000");
    var tiny = post("/api/v1/admin/credit/accounts/" + id + "/adjust", admin, Map.of("amountPaise", Long.MIN_VALUE, "note", "x"));
    assertThat(fields(tiny.getBody()).get("amountPaise")).isEqualTo("must be at least -100000000");
    var bigLimit = call(HttpMethod.PUT, "/api/v1/admin/credit/accounts/" + id + "/limit", admin, Map.of("limitPaise", 100_000_001L), null);
    assertThat(fields(bigLimit.getBody()).get("limitPaise")).isEqualTo("must be at most 100000000");

    String cashier = token("cashier@vijaysupermall.local");
    Object rice = ((List<Map>) get("/api/v1/pos/products?q=Basmati", cashier).getBody().get("items")).get(0).get("productId");
    var qty = post("/api/v1/pos/quote", cashier, Map.of("items", List.of(Map.of("productId", rice, "qty", 10000))));
    assertThat(qty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(qty.getBody())).containsKey("qty");
    var disc = post("/api/v1/pos/quote", cashier, Map.of("items", List.of(Map.of("productId", rice, "qty", 1)),
        "manualDiscount", Map.of("amountPaise", 100_000_001L, "reason", "x")));
    assertThat(disc.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(disc.getBody()).get("manualDiscount.amountPaise")).isEqualTo("must be at most 100000000");
    var pay = call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier,
        Map.of("customerMobile", mobile(), "payments", List.of(Map.of("mode", "CASH", "amountPaise", Long.MAX_VALUE))), uuid());
    assertThat(pay.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var q = get("/api/v1/admin/customers?q=" + "q".repeat(101), admin);
    assertThat(q.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(q.getBody()).get("q")).isEqualTo("must be at most 100 characters");
  }

  @Test
  void purchaseLinesAndNamesAreBounded() {
    String admin = adminToken();
    Object pid = productByName(admin, "Orange Juice").get("id");
    var line = Map.of("productId", pid, "qty", 1_000_001, "unitCostPaise", 100);
    var qty = call(HttpMethod.POST, "/api/v1/admin/purchases", admin, Map.of("supplierName", "S", "items", List.of(line)), uuid());
    assertThat(qty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(qty.getBody()).get("items[0].qty")).isEqualTo("must be 1 to 1000000");
    var price = call(HttpMethod.POST, "/api/v1/admin/purchases", admin,
        Map.of("supplierName", "S", "items", List.of(Map.of("productId", pid, "qty", 1, "unitCostPaise", 100_000_001L))), uuid());
    assertThat(fields(price.getBody()).get("items[0].unitCostPaise")).isEqualTo("must be at most 100000000");
    var supplier = call(HttpMethod.POST, "/api/v1/admin/purchases", admin,
        Map.of("supplierName", "s".repeat(121), "items", List.of(Map.of("productId", pid, "qty", 1, "unitCostPaise", 100))), uuid());
    assertThat(fields(supplier.getBody())).containsKey("supplierName");
  }

  // ---------- cart ----------
  @Test
  void theCouponPickerRefusesACartThatTheQuoteRefuses() {
    String t = token(freshEmail());
    Map body = Map.of("items", List.of(Map.of("productId", uuid(), "qty", 1)));
    var quote = post("/api/v1/cart/quote", t, body);
    var picker = post("/api/v1/cart/coupons", t, body);
    assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(picker.getStatusCode()).isEqualTo(quote.getStatusCode());
    assertThat(picker.getBody().get("code")).isEqualTo(quote.getBody().get("code"));
    assertThat(fields(picker.getBody())).isEqualTo(fields(quote.getBody()));
  }

  @Test
  void allCoinsOnACartWithNothingRedeemableWarns() {
    String t = token(freshEmail());
    Object pid = productByName(t, "Potato Chips").get("id");
    jdbc.update("update product set coin_redeemable = false where id = ?::uuid", pid.toString());
    try {
      var q = post("/api/v1/cart/quote", t, Map.of("items", List.of(Map.of("productId", pid, "qty", 1)), "coinMode", "ALL"));
      assertThat(q.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(((Number) q.getBody().get("coinsUsed")).intValue()).isZero();
      assertThat((List<String>) q.getBody().get("warnings")).contains("None of the items in the cart can be paid with coins");
    } finally {
      jdbc.update("update product set coin_redeemable = true where id = ?::uuid", pid.toString());
    }
  }

  // ---------- wallet, payment methods, shop ----------
  @Test
  void theWalletTellsHowLongCoinsLast() {
    var w = get("/api/v1/wallet", token(freshEmail()));
    assertThat(((Number) w.getBody().get("expiryMonths")).intValue()).isEqualTo(jdbc.queryForObject("select expiry_months from coin_setting", Integer.class));
  }

  @Test
  void aPaymentMethodSaveMustNameEveryMethodOnce() {
    String admin = adminToken();
    var empty = call(HttpMethod.PUT, "/api/v1/admin/payment-methods", admin, List.of(), null);
    assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(empty.getBody()).get("methods")).isEqualTo("must list every method");
    var partial = call(HttpMethod.PUT, "/api/v1/admin/payment-methods", admin, List.of(Map.of("code", "COD", "enabled", true)), null);
    assertThat(fields(partial.getBody()).get("methods")).isEqualTo("must list every method");
    var twice = call(HttpMethod.PUT, "/api/v1/admin/payment-methods", admin,
        List.of(Map.of("code", "COD", "enabled", true), Map.of("code", "COD", "enabled", false)), null);
    assertThat(fields(twice.getBody()).get("methods")).isEqualTo("duplicate code \"COD\"");
    var every = call(HttpMethod.PUT, "/api/v1/admin/payment-methods", admin,
        List.of(Map.of("code", "COD", "enabled", true), Map.of("code", "ONLINE", "enabled", false)), null);
    assertThat(every.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private static Map<String, Object> profile(boolean orders) {
    List<Map<String, Object>> hours = new java.util.ArrayList<>();
    for (String d : List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")) hours.add(Map.of("day", d, "open", "08:00", "close", "22:00", "closed", false));
    return Map.of("hours", hours, "onlineOrdersEnabled", orders, "onlineOrdersMessage", "");
  }

  @Test
  void aBlankPauseMessageIsReplacedByTheDefaultText() {
    String admin = adminToken();
    try {
      var off = call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, profile(false), null);
      assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
      var shop = get("/api/v1/shop-settings", null);
      assertThat(shop.getBody().get("onlineOrdersMessage"))
          .isEqualTo("We are not taking online orders right now. Please visit the shop or try again later.");
    } finally {
      call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, profile(true), null);
    }
    assertThat(get("/api/v1/shop-settings", null).getBody().get("onlineOrdersMessage")).isEqualTo("");
  }

  // ---------- orders ----------
  private String placeOrder(String token, Object pid, String fulfilment) {
    var items = List.of(Map.of("productId", pid, "qty", 2));
    Map<String, Object> req = Map.of("items", items, "coinMode", "NONE", "fulfilment", fulfilment);
    long total = ((Number) post("/api/v1/cart/quote", token, req).getBody().get("totalPaise")).longValue();
    Map<String, Object> order = new java.util.HashMap<>(Map.of("items", items, "coinMode", "NONE", "fulfilment", fulfilment,
        "paymentMethod", "COD", "expectedTotalPaise", total));
    if ("DELIVERY".equals(fulfilment)) {
      order.put("addressId", post("/api/v1/me/addresses", token, Map.of("label", "Home", "line1", "1 Main", "area", "Centre", "pincode", "380001"))
          .getBody().get("id"));
    }
    var res = call(HttpMethod.POST, "/api/v1/orders", token, order, uuid());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  @Test
  void aCancelledOrderReportsNoCoinsEarnedAnywhere() {
    String admin = adminToken();
    String t = token(freshEmail());
    Object pid = productByName(t, "Basmati Rice").get("id");
    String id = placeOrder(t, pid, "PICKUP");
    assertThat(((Number) get("/api/v1/orders/" + id, t).getBody().get("coinsEarned")).intValue()).isGreaterThan(0);
    assertThat(post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CANCELLED")).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) get("/api/v1/orders/" + id, t).getBody().get("coinsEarned")).intValue()).isZero();
    assertThat(((Number) get("/api/v1/admin/orders/" + id, admin).getBody().get("coinsEarned")).intValue()).isZero();
    assertThat(get("/api/v1/wallet", t).getBody().get("coins")).isEqualTo(0);
  }

  @Test
  void statusMovesMustFitTheFulfilmentAndNeverLeaveStockDeductedOnPlaced() {
    String admin = adminToken();
    String t = token(freshEmail());
    Object pid = productByName(t, "Basmati Rice").get("id");
    String pickup = placeOrder(t, pid, "PICKUP");
    String delivery = placeOrder(t, pid, "DELIVERY");
    for (String bad : List.of("OUT_FOR_DELIVERY", "DELIVERED")) {
      var res = post("/api/v1/admin/orders/" + pickup + "/status", admin, Map.of("status", bad));
      assertThat(res.getStatusCode()).as(bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
      assertThat(res.getBody().get("code")).isEqualTo("INVALID_TRANSITION");
    }
    for (String bad : List.of("READY_FOR_PICKUP", "PICKED_UP")) {
      assertThat(post("/api/v1/admin/orders/" + delivery + "/status", admin, Map.of("status", bad)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
    assertThat(post("/api/v1/admin/orders/" + pickup + "/status", admin, Map.of("status", "CONFIRMED")).getStatusCode()).isEqualTo(HttpStatus.OK);
    var back = post("/api/v1/admin/orders/" + pickup + "/status", admin, Map.of("status", "PLACED"));
    assertThat(back.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(back.getBody().get("code")).isEqualTo("INVALID_TRANSITION");
    assertThat(post("/api/v1/admin/orders/" + pickup + "/status", admin, Map.of("status", "READY_FOR_PICKUP")).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(post("/api/v1/admin/orders/" + pickup + "/status", admin, Map.of("status", "PACKED")).getStatusCode()).as("other backward moves stay").isEqualTo(HttpStatus.OK);
    post("/api/v1/admin/orders/" + pickup + "/status", admin, Map.of("status", "CANCELLED"));
    post("/api/v1/admin/orders/" + delivery + "/status", admin, Map.of("status", "CANCELLED"));
  }
}
