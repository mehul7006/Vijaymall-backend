package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Item-specific coupons (admin, public view, quote, picker, counter bill) and what returns refund when the order used a coupon,
 * under both policies, for online orders and counter bills, against real PostgreSQL.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class CouponItemReturnIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  private UUID juice;
  private UUID rice;
  private UUID riceGroup;

  @BeforeEach
  void ids() {
    restock("Orange Juice", 100);
    restock("Basmati Rice", 100);
    juice = jdbc.queryForObject("select id from product where name_en = 'Orange Juice' order by id limit 1", UUID.class);
    rice = jdbc.queryForObject("select id from product where name_en = 'Basmati Rice' and pack_size = '1 kg'", UUID.class);
    riceGroup = jdbc.queryForObject("select product_group_id from product where id = ?", UUID.class, rice);
    put("returns.couponPolicy", "KEEP");
  }

  private void put(String key, String value) {
    var res = call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(Map.of("key", key, "value", value))), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private String code() { return "I" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }

  private Map<String, Object> body(String code, Map<String, Object> extra) {
    Map<String, Object> b = new HashMap<>(Map.of("code", code, "kind", "PERCENT", "value", 10));
    b.putAll(extra);
    return b;
  }

  private Map create(Map<String, Object> body) {
    var res = post("/api/v1/admin/coupons", adminToken(), body);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private Map<String, Object> cart(String code, int juiceQty, int riceQty) {
    Map<String, Object> req = new HashMap<>(Map.of("items", List.of(Map.of("productId", juice, "qty", juiceQty),
        Map.of("productId", rice, "qty", riceQty)), "coinMode", "NONE", "fulfilment", "PICKUP"));
    if (code != null) req.put("couponCode", code);
    return req;
  }

  private long n(Object o) { return ((Number) o).longValue(); }

  @Test
  void adminListsItemsWithNamesValidatesThemAndThePublicViewSaysWhatItIsFor() {
    String code = code();
    Map made = create(body(code, Map.of("productIds", List.of(juice), "productGroupIds", List.of(riceGroup))));
    Map applies = (Map) made.get("appliesTo");
    assertThat(applies.get("type")).isEqualTo("ITEMS");
    assertThat((List<Map>) applies.get("products")).extracting(m -> m.get("name")).first().asString().startsWith("Orange Juice");
    assertThat((List<Map>) applies.get("productGroups")).hasSize(1);
    assertThat((List) made.get("productIds")).hasSize(1);
    String id = (String) made.get("id");
    assertThat(get("/api/v1/admin/coupons/" + id, adminToken()).getBody().get("code")).isEqualTo(code);
    assertThat(getList("/api/v1/admin/coupons", adminToken())).anySatisfy(c -> {
      assertThat(c.get("id")).isEqualTo(id);
      assertThat(((Map) c.get("appliesTo")).get("type")).isEqualTo("ITEMS");
    });
    assertThat(get("/api/v1/admin/coupons/" + UUID.randomUUID(), adminToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    var pub = getList("/api/v1/coupons", null).stream().filter(c -> code.equals(c.get("code"))).findFirst().orElseThrow();
    assertThat(pub).containsEntry("appliesTo", "ITEMS");
    assertThat((String) pub.get("appliesToText")).contains("Orange Juice");
    assertThat((List) pub.get("appliesToNames")).hasSize(2);

    String catId = jdbc.queryForObject("select category_id::text from product where id = ?", String.class, juice);
    Map mixed = create(body(code(), Map.of("categoryIds", List.of(catId), "productIds", List.of(rice))));
    assertThat(((Map) mixed.get("appliesTo")).get("type")).isEqualTo("MIXED");
    Map cats = create(body(code(), Map.of("categoryIds", List.of(catId))));
    assertThat(((Map) cats.get("appliesTo")).get("type")).isEqualTo("CATEGORIES");
    assertThat(((Map) create(body(code(), Map.of())).get("appliesTo")).get("type")).isEqualTo("ALL");

    var unknown = post("/api/v1/admin/coupons", adminToken(), body(code(), Map.of("productIds", List.of(juice, UUID.randomUUID()))));
    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(unknown.getBody().toString()).contains("productIds[1]");
    assertThat(post("/api/v1/admin/coupons", adminToken(), body(code(), Map.of("productGroupIds", List.of(UUID.randomUUID())))).getBody().toString())
        .contains("productGroupIds[0]");
    assertThat(post("/api/v1/admin/coupons", adminToken(), body(code(), Map.of("categoryIds", List.of(UUID.randomUUID())))).getBody().toString())
        .contains("categoryIds[0]");
    List<UUID> many = new ArrayList<>();
    for (int i = 0; i < 201; i++) many.add(UUID.randomUUID());
    assertThat(post("/api/v1/admin/coupons", adminToken(), body(code(), Map.of("productIds", many))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    var upd = call(HttpMethod.PUT, "/api/v1/admin/coupons/" + id, adminToken(), body(code, Map.of("productIds", List.of(rice))), null);
    assertThat(((Map) upd.getBody().get("appliesTo")).get("type")).isEqualTo("ITEMS");
    assertThat((List) upd.getBody().get("productGroupIds")).isEmpty();
  }

  @Test
  void theQuoteDiscountsOnlyTheCoveredLinesAndTellsWhatTheCouponIsFor() {
    String token = token(freshEmail());
    String code = code();
    create(body(code, Map.of("productIds", List.of(juice))));
    Map q = post("/api/v1/cart/quote", token, cart(code, 2, 1)).getBody();
    List<Map> lines = (List<Map>) q.get("lines");
    Map juiceLine = lines.stream().filter(l -> juice.toString().equals(l.get("productId"))).findFirst().orElseThrow();
    Map riceLine = lines.stream().filter(l -> rice.toString().equals(l.get("productId"))).findFirst().orElseThrow();
    assertThat(n(juiceLine.get("couponDiscountPaise"))).isEqualTo(n(juiceLine.get("lineTotalPaise")) * 10 / 100);
    assertThat(n(riceLine.get("couponDiscountPaise"))).isZero();
    assertThat(n(q.get("couponDiscountPaise"))).isEqualTo(n(juiceLine.get("couponDiscountPaise")));

    String ricesOnly = code();
    create(body(ricesOnly, Map.of("productGroupIds", List.of(riceGroup), "minOrderPaise", 1)));
    Map riceQuote = post("/api/v1/cart/quote", token, Map.of("items", List.of(Map.of("productId", juice, "qty", 1)), "coinMode", "NONE",
        "fulfilment", "PICKUP", "couponCode", ricesOnly)).getBody();
    assertThat(riceQuote.get("code")).isEqualTo("COUPON_INVALID");
    assertThat(((Map) riceQuote.get("details")).get("reason")).isEqualTo("ITEM");
    assertThat((String) riceQuote.get("message")).startsWith("This coupon is for ").contains("none are in your cart");

    long juiceTotal = n(juiceLine.get("lineTotalPaise"));
    String minCode = code();
    create(body(minCode, Map.of("productIds", List.of(juice), "minOrderPaise", juiceTotal + 1)));
    Map low = post("/api/v1/cart/quote", token, cart(minCode, 2, 1)).getBody();
    assertThat(((Map) low.get("details")).get("reason")).as("the rice does not count towards the minimum").isEqualTo("MIN_ORDER");
    assertThat(n(((Map) low.get("details")).get("shortfallPaise"))).isEqualTo(1);
  }

  @Test
  void thePickerShowsTheSavingAndWhichLinesItCovers() {
    String token = token(freshEmail());
    String code = code();
    create(body(code, Map.of("productIds", List.of(juice))));
    var options = (List<Map>) post("/api/v1/cart/coupons", token, cart(null, 2, 1)).getBody().get("_array");
    Map mine = options.stream().filter(o -> code.equals(o.get("code"))).findFirst().orElseThrow();
    assertThat(mine).containsEntry("applicable", true).containsEntry("appliesTo", "ITEMS");
    List<Map> covered = (List<Map>) mine.get("coveredLines");
    assertThat(covered).hasSize(1);
    assertThat(covered.get(0).get("productId")).isEqualTo(juice.toString());
    assertThat(n(covered.get(0).get("discountPaise"))).isEqualTo(n(mine.get("discountPaise")));
  }

  private Map posSale(String cashier, String mobile, String code, int juiceQty, int riceQty) {
    Map cart = new HashMap<>(Map.of("items", List.of(Map.of("productId", juice, "qty", juiceQty), Map.of("productId", rice, "qty", riceQty)),
        "customerMobile", mobile, "couponCode", code));
    Map quote = post("/api/v1/pos/quote", cashier, cart).getBody();
    var res = call(HttpMethod.POST, "/api/v1/pos/sales", cashier,
        Map.of("cart", cart, "payments", List.of(Map.of("mode", "CASH", "amountPaise", quote.get("totalPaise")))), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private ResponseEntity<Map> posReturn(String cashier, Object saleId, Object itemId, int qty, boolean preview) {
    return call(HttpMethod.POST, "/api/v1/pos/sales/" + saleId + "/returns" + (preview ? "/preview" : ""), cashier,
        Map.of("items", List.of(Map.of("saleItemId", itemId, "qty", qty)), "reason", "test"), preview ? null : UUID.randomUUID().toString());
  }

  @Test
  void theCounterBillDiscountsCoveredLinesAndReturnsFollowTheShare() {
    String cashier = token(CASHIER);
    String mobile = "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    String code = code();
    create(body(code, Map.of("productIds", List.of(juice))));
    Map sale = posSale(cashier, mobile, code, 2, 1);
    List<Map> items = (List<Map>) sale.get("items");
    Map j = items.stream().filter(i -> juice.toString().equals(i.get("productId"))).findFirst().orElseThrow();
    Map r = items.stream().filter(i -> rice.toString().equals(i.get("productId"))).findFirst().orElseThrow();
    assertThat(n(j.get("discountPaise"))).isEqualTo(n(sale.get("couponDiscountPaise")));
    assertThat(n(r.get("discountPaise"))).isZero();

    var ricePreview = posReturn(cashier, sale.get("id"), r.get("id"), 1, true);
    assertThat(n(ricePreview.getBody().get("refundPaise"))).as("an uncovered line comes back at its full price").isEqualTo(n(r.get("netPaise")));
    var preview = posReturn(cashier, sale.get("id"), j.get("id"), 1, true);
    var done = posReturn(cashier, sale.get("id"), j.get("id"), 1, false);
    assertThat(done.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(n(done.getBody().get("refundPaise"))).isEqualTo(n(preview.getBody().get("refundPaise")));
    assertThat(n(done.getBody().get("refundPaise"))).isEqualTo(n(j.get("netPaise")) / 2);
    assertThat(n(preview.getBody().get("couponShareRemovedPaise"))).isEqualTo(n(sale.get("couponDiscountPaise")) / 2);
    assertThat(get("/api/v1/pos/sales/" + sale.get("id") + "/returns/" + done.getBody().get("id") + "/receipt?width=32", cashier).getBody().toString())
        .contains("Coupon share");
  }

  @Test
  void theCounterBillTakesTheCouponBackBelowTheMinimumWhenThePolicyIsOn() {
    put("returns.couponPolicy", "CLAWBACK_BELOW_MINIMUM");
    String cashier = token(CASHIER);
    String mobile = "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    long juicePrice = jdbc.queryForObject("select price_paise from product where id = ?", Long.class, juice);
    String code = code();
    create(body(code, Map.of("productIds", List.of(juice), "minOrderPaise", juicePrice * 2)));
    Map sale = posSale(cashier, mobile, code, 2, 1);
    Map j = ((List<Map>) sale.get("items")).stream().filter(i -> juice.toString().equals(i.get("productId"))).findFirst().orElseThrow();
    long net = n(j.get("netPaise"));
    long coupon = n(sale.get("couponDiscountPaise"));

    var first = posReturn(cashier, sale.get("id"), j.get("id"), 1, false);
    assertThat(n(first.getBody().get("couponClawbackPaise"))).isEqualTo(coupon / 2);
    assertThat(n(first.getBody().get("refundPaise"))).isEqualTo(net / 2 - coupon / 2);
    var second = posReturn(cashier, sale.get("id"), j.get("id"), 1, false);
    assertThat(n(first.getBody().get("refundPaise")) + n(second.getBody().get("refundPaise"))).isEqualTo(net);
    assertThat(n(second.getBody().get("couponClawbackPaise"))).isZero();
  }

  private String deliveredOrder(String token, String code, int juiceQty, int riceQty) {
    Map<String, Object> req = cart(code, juiceQty, riceQty);
    long total = n(post("/api/v1/cart/quote", token, req).getBody().get("totalPaise"));
    Map<String, Object> order = new HashMap<>(req);
    order.put("paymentMethod", "COD");
    order.put("expectedTotalPaise", total);
    var res = call(HttpMethod.POST, "/api/v1/orders", token, order, UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    String id = (String) res.getBody().get("id");
    for (String s : List.of("CONFIRMED", "PICKED_UP")) {
      assertThat(post("/api/v1/admin/orders/" + id + "/status", adminToken(), Map.of("status", s)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    return id;
  }

  private ResponseEntity<Map> orderReturn(String orderId, UUID product, int qty, boolean preview) {
    return call(HttpMethod.POST, "/api/v1/admin/orders/" + orderId + "/returns" + (preview ? "/preview" : ""), adminToken(),
        Map.of("reason", "damaged", "items", List.of(Map.of("productId", product, "qty", qty))), preview ? null : UUID.randomUUID().toString());
  }

  @Test
  void anOnlineReturnKeepsTheCouponShareOfTheKeptItems() {
    String token = token(freshEmail());
    String code = code();
    create(body(code, Map.of("productIds", List.of(juice))));
    String orderId = deliveredOrder(token, code, 2, 1);
    List<Map> lines = (List<Map>) get("/api/v1/orders/" + orderId, token).getBody().get("items");
    Map j = lines.stream().filter(l -> juice.toString().equals(l.get("productId"))).findFirst().orElseThrow();
    Map r = lines.stream().filter(l -> rice.toString().equals(l.get("productId"))).findFirst().orElseThrow();
    long share = n(j.get("couponDiscountPaise"));
    assertThat(share).isPositive();

    var rp = orderReturn(orderId, rice, 1, true);
    assertThat(n(rp.getBody().get("refundPaise"))).isEqualTo(n(r.get("lineTotalPaise")));
    assertThat(n(rp.getBody().get("deliveryFeeRefundedPaise"))).isZero();

    var preview = orderReturn(orderId, juice, 1, true);
    assertThat(n(preview.getBody().get("couponShareRemovedPaise"))).isEqualTo(share / 2);
    assertThat(n(preview.getBody().get("couponClawbackPaise"))).isZero();
    var one = orderReturn(orderId, juice, 1, false);
    assertThat(n(one.getBody().get("refundPaise"))).isEqualTo(n(preview.getBody().get("refundPaise")));
    var two = orderReturn(orderId, juice, 1, false);
    assertThat(n(one.getBody().get("refundPaise")) + n(two.getBody().get("refundPaise")))
        .isEqualTo(n(j.get("lineTotalPaise")) - share);
  }

  @Test
  void anOnlineReturnTakesTheCouponBackBelowTheMinimumWhenThePolicyIsOn() {
    put("returns.couponPolicy", "CLAWBACK_BELOW_MINIMUM");
    String token = token(freshEmail());
    long juicePrice = jdbc.queryForObject("select price_paise from product where id = ?", Long.class, juice);
    String code = code();
    create(body(code, Map.of("productIds", List.of(juice), "minOrderPaise", juicePrice * 2)));
    String orderId = deliveredOrder(token, code, 2, 1);
    List<Map> lines = (List<Map>) get("/api/v1/orders/" + orderId, token).getBody().get("items");
    Map j = lines.stream().filter(l -> juice.toString().equals(l.get("productId"))).findFirst().orElseThrow();
    long share = n(j.get("couponDiscountPaise"));

    var preview = orderReturn(orderId, juice, 1, true);
    assertThat(preview.getBody().get("couponPolicy")).isEqualTo("CLAWBACK_BELOW_MINIMUM");
    assertThat(n(preview.getBody().get("couponClawbackPaise"))).isEqualTo(share / 2);
    var one = orderReturn(orderId, juice, 1, false);
    assertThat(n(one.getBody().get("couponClawbackPaise"))).isEqualTo(share / 2);
    assertThat(n(one.getBody().get("refundPaise"))).isEqualTo(n(j.get("lineTotalPaise")) / 2 - share / 2 - share / 2);
    var two = orderReturn(orderId, juice, 1, false);
    assertThat(n(two.getBody().get("couponClawbackPaise"))).isZero();
    assertThat(n(one.getBody().get("refundPaise")) + n(two.getBody().get("refundPaise"))).isEqualTo(n(j.get("lineTotalPaise")) - share);
    assertThat(jdbc.queryForObject("select coupon_min_order_paise from orders where id = ?::uuid", Long.class, orderId)).isEqualTo(juicePrice * 2);
  }
}
