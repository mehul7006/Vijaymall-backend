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
import org.springframework.http.ResponseEntity;

/** Order API rules: pricing, delivery, coins, validation, history and admin listing. */
@SuppressWarnings({"rawtypes", "unchecked"})
class OrderApiIT extends IntegrationTestBase {

  @org.junit.jupiter.api.BeforeEach
  void stock() {
    restock("Basmati Rice", 50);
    restock("Orange Juice", 30);
    restock("Potato Chips", 30);
  }

  private Map<String, Object> cart(Object pid, int qty, Map<String, Object> extra) {
    Map<String, Object> m = new HashMap<>(Map.of("items", List.of(Map.of("productId", pid, "qty", qty)),
        "coinMode", "NONE", "fulfilment", "PICKUP"));
    m.putAll(extra);
    return m;
  }

  private ResponseEntity<Map> quote(String t, Map<String, Object> cart) { return post("/api/v1/cart/quote", t, cart); }

  private Map<String, Object> placeBody(String t, Map<String, Object> cart, String method) {
    Map<String, Object> m = new HashMap<>(cart);
    m.put("paymentMethod", method);
    m.put("expectedTotalPaise", ((Number) quote(t, cart).getBody().get("totalPaise")).longValue());
    return m;
  }

  private ResponseEntity<Map> place(String t, Map<String, Object> cart) {
    return call(HttpMethod.POST, "/api/v1/orders", t, placeBody(t, cart, "COD"), UUID.randomUUID().toString());
  }

  private String address(String t) {
    return (String) post("/api/v1/me/addresses", t, Map.of("label", "Home", "line1", "1 Road", "area", "A", "pincode", "390001")).getBody().get("id");
  }

  private Object product(String t, String name) { return productByName(t, name).get("id"); }

  /** Gives a fresh customer coins by delivering one big pickup order, then returns their wallet balance. */
  private int earnCoins(String t, String admin) {
    var res = place(t, cart(product(t, "Basmati Rice"), 10, Map.of()));
    String id = (String) res.getBody().get("id");
    for (String s : List.of("CONFIRMED", "PACKED", "READY_FOR_PICKUP", "PICKED_UP")) {
      assertThat(post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", s)).getStatusCode()).as(s).isEqualTo(HttpStatus.OK);
    }
    return ((Number) get("/api/v1/wallet", t).getBody().get("coins")).intValue();
  }

  @Test
  void deliveryIsFreeForNow() {
    String t = token(freshEmail());
    Object chips = product(t, "Potato Chips");
    var delivery = quote(t, cart(chips, 1, Map.of("fulfilment", "DELIVERY"))).getBody();
    var pickup = quote(t, cart(chips, 1, Map.of())).getBody();
    // The shipped default is a zero fee. The fee rule itself (below the free limit, none for pickup) is covered in
    // QuoteStepsTest, which sets its own fee.
    assertThat(delivery.get("deliveryFeePaise")).isEqualTo(0);
    assertThat(pickup.get("deliveryFeePaise")).isEqualTo(0);
    assertThat(delivery.get("totalPaise")).isEqualTo(pickup.get("totalPaise"));
  }

  @Test
  void sameProductTwiceInTheCartIsMerged() {
    String t = token(freshEmail());
    Object pid = product(t, "Potato Chips");
    var q = quote(t, Map.of("items", List.of(Map.of("productId", pid, "qty", 1), Map.of("productId", pid, "qty", 2)),
        "coinMode", "NONE", "fulfilment", "PICKUP")).getBody();
    assertThat((List<Map<String, Object>>) q.get("lines")).singleElement().satisfies(l -> assertThat(l.get("qty")).isEqualTo(3));
    assertThat(q.get("subtotalPaise")).isEqualTo(5400);
  }

  @Test
  void badCartsAreRejected() {
    String t = token(freshEmail());
    Object pid = product(t, "Potato Chips");
    assertThat(quote(t, cart(pid, 0, Map.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(quote(t, cart(pid, 100, Map.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(quote(t, cart(UUID.randomUUID(), 1, Map.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(quote(t, Map.of("items", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void placingNeedsAnIdempotencyKeyAMethodAndAnAddressForDelivery() {
    String t = token(freshEmail());
    Object pid = product(t, "Potato Chips");
    var body = placeBody(t, cart(pid, 1, Map.of()), "COD");
    assertThat(call(HttpMethod.POST, "/api/v1/orders", t, body, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, "/api/v1/orders", t, placeBody(t, cart(pid, 1, Map.of()), "BITCOIN"), UUID.randomUUID().toString())
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    var delivery = cart(pid, 1, Map.of("fulfilment", "DELIVERY"));
    assertThat(call(HttpMethod.POST, "/api/v1/orders", t, placeBody(t, delivery, "COD"), UUID.randomUUID().toString()).getStatusCode())
        .as("delivery without address").isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> withOther = placeBody(t, delivery, "COD");
    withOther.put("addressId", address(token(freshEmail())));
    assertThat(call(HttpMethod.POST, "/api/v1/orders", t, withOther, UUID.randomUUID().toString()).getStatusCode())
        .as("someone else's address").isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void deliveryOrderKeepsAnAddressSnapshotEvenIfTheAddressChangesLater() {
    String t = token(freshEmail());
    String addrId = address(t);
    Map<String, Object> body = placeBody(t, cart(product(t, "Potato Chips"), 1, Map.of("fulfilment", "DELIVERY")), "COD");
    body.put("addressId", addrId);
    var order = call(HttpMethod.POST, "/api/v1/orders", t, body, UUID.randomUUID().toString());
    assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    call(HttpMethod.PUT, "/api/v1/me/addresses/" + addrId, t,
        Map.of("label", "New", "line1", "Elsewhere", "area", "B", "pincode", "390002"), null);
    var again = get("/api/v1/orders/" + order.getBody().get("id"), t).getBody();
    assertThat((Map<String, Object>) again.get("address")).containsEntry("line1", "1 Road");
  }

  @Test
  void sameIdempotencyKeyInParallelCreatesOneOrder() throws Exception {
    String t = token(freshEmail());
    var body = placeBody(t, cart(product(t, "Potato Chips"), 1, Map.of()), "COD");
    String key = UUID.randomUUID().toString();
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = java.util.stream.IntStream.range(0, 5)
          .mapToObj(_ -> pool.submit(() -> call(HttpMethod.POST, "/api/v1/orders", t, body, key))).toList();
      java.util.Set<Object> ids = new java.util.HashSet<>();
      for (var f : futures) {
        var r = f.get();
        assertThat(r.getStatusCode().value()).isIn(200, 201);
        ids.add(r.getBody().get("id"));
      }
      assertThat(ids).hasSize(1);
    }
    var list = (List<Map<String, Object>>) get("/api/v1/orders", t).getBody().get("items");
    assertThat(list).hasSize(1);
  }

  @Test
  void coinsCanPayPartOfAnOrderAndAreReturnedOnCancel() {
    String admin = adminToken();
    String t = token(freshEmail());
    int coins = earnCoins(t, admin);
    assertThat(coins).isPositive();

    Object pid = product(t, "Orange Juice");
    var all = cart(pid, 2, Map.of("coinMode", "ALL"));
    var q = quote(t, all).getBody();
    int used = ((Number) q.get("coinsUsed")).intValue();
    assertThat(used).isPositive().isLessThanOrEqualTo(coins);
    assertThat(((Number) q.get("coinDiscountPaise")).longValue()).isEqualTo(used * 100L);

    var half = quote(t, cart(pid, 2, Map.of("coinMode", "PERCENT", "percent", 50))).getBody();
    assertThat(((Number) half.get("coinsUsed")).intValue()).isLessThanOrEqualTo(used);

    var order = call(HttpMethod.POST, "/api/v1/orders", t, placeBody(t, all, "COD"), UUID.randomUUID().toString());
    assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(order.getBody().get("coinsUsed")).isEqualTo(used);
    assertThat(get("/api/v1/wallet", t).getBody().get("coins")).isEqualTo(coins - used);

    post("/api/v1/orders/" + order.getBody().get("id") + "/cancel", t, null);
    assertThat(get("/api/v1/wallet", t).getBody().get("coins")).isEqualTo(coins);
    var ledger = (List<Map<String, Object>>) get("/api/v1/wallet/ledger", t).getBody().get("items");
    assertThat(ledger).extracting(r -> r.get("kind")).contains("EARN", "REDEEM", "REVERSE");
    assertThat(ledger).filteredOn(r -> "REDEEM".equals(r.get("kind"))).singleElement()
        .satisfies(r -> assertThat(r.get("orderNo")).isEqualTo(order.getBody().get("orderNo")));
  }

  @Test
  void coinsCannotPayForItemsThatExcludeThem() {
    String admin = adminToken();
    String t = token(freshEmail());
    earnCoins(t, admin);
    var cat = (String) post("/api/v1/admin/categories", admin, Map.of("nameEn", "NoCoin " + UUID.randomUUID(), "nameGu", "x")).getBody().get("id");
    var item = post("/api/v1/admin/products", admin, Map.of("categoryId", cat, "nameEn", "Gold " + UUID.randomUUID(), "nameGu", "x",
        "mrpPaise", 10000, "pricePaise", 10000, "qtyOnHand", 5, "coinRedeemable", false, "earnsCoins", false)).getBody();
    var q = quote(t, cart(item.get("id"), 1, Map.of("coinMode", "ALL"))).getBody();
    assertThat(q.get("coinsUsed")).isEqualTo(0);
    assertThat(q.get("coinsToEarn")).isEqualTo(0);
  }

  @Test
  void pickupOrderEarnsCoinsOnlyWhenPickedUp() {
    String admin = adminToken();
    String t = token(freshEmail());
    var order = place(t, cart(product(t, "Basmati Rice"), 10, Map.of())).getBody();
    int toEarn = ((Number) order.get("coinsEarned")).intValue();
    assertThat(toEarn).isPositive();
    assertThat(get("/api/v1/wallet", t).getBody().get("coins")).isEqualTo(0);
    String id = (String) order.get("id");
    post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CONFIRMED"));
    post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "PACKED"));
    var wrong = post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "OUT_FOR_DELIVERY"));
    assertThat(wrong.getBody().get("code")).as("pickup orders cannot go out for delivery").isEqualTo("ILLEGAL_TRANSITION");
    post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "READY_FOR_PICKUP"));
    var done = post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "PICKED_UP"));
    assertThat(done.getBody()).containsEntry("status", "PICKED_UP").containsEntry("paymentStatus", "CAPTURED");
    assertThat(get("/api/v1/wallet", t).getBody().get("coins")).isEqualTo(toEarn);
    var timeline = (List<Map<String, Object>>) done.getBody().get("timeline");
    assertThat(timeline).extracting(s -> s.get("status")).containsExactly("PLACED", "CONFIRMED", "PACKED", "READY_FOR_PICKUP", "PICKED_UP");
    assertThat(done.getBody()).containsKeys("customerName", "customerEmail", "customerMobile");
  }

  @Test
  void customersCancelOnlyBeforePackingAndOnlyOnce() {
    String admin = adminToken();
    String t = token(freshEmail());
    String id = (String) place(t, cart(product(t, "Potato Chips"), 1, Map.of())).getBody().get("id");
    assertThat(post("/api/v1/orders/" + id + "/cancel", t, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    var again = post("/api/v1/orders/" + id + "/cancel", t, null);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(again.getBody().get("code")).isEqualTo("ILLEGAL_TRANSITION");
    assertThat(post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CONFIRMED")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(post("/api/v1/orders/" + UUID.randomUUID() + "/cancel", t, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void ordersAreListedNewestFirstAndPaged() {
    String t = token(freshEmail());
    Object pid = product(t, "Potato Chips");
    String first = (String) place(t, cart(pid, 1, Map.of())).getBody().get("id");
    String second = (String) place(t, cart(pid, 1, Map.of())).getBody().get("id");
    var page = get("/api/v1/orders?size=1", t).getBody();
    assertThat(((Number) page.get("total")).intValue()).isEqualTo(2);
    assertThat((List<Map<String, Object>>) page.get("items")).singleElement().satisfies(o -> assertThat(o.get("id")).isEqualTo(second));
    var next = get("/api/v1/orders?size=1&page=1", t).getBody();
    assertThat((List<Map<String, Object>>) next.get("items")).singleElement().satisfies(o -> {
      assertThat(o.get("id")).isEqualTo(first);
      assertThat(o.get("itemCount")).isEqualTo(1);
    });
    assertThat(get("/api/v1/orders/" + UUID.randomUUID(), t).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void adminFiltersOrdersByStatusAndSeesCustomerDetails() {
    String admin = adminToken();
    String email = freshEmail();
    String t = token(email);
    String id = (String) place(t, cart(product(t, "Potato Chips"), 1, Map.of())).getBody().get("id");
    var placed = (List<Map<String, Object>>) get("/api/v1/admin/orders?status=PLACED&size=50", admin).getBody().get("items");
    assertThat(placed).allSatisfy(o -> assertThat(o.get("status")).isEqualTo("PLACED"));
    assertThat(placed).anyMatch(o -> id.equals(o.get("id")) && email.equals(o.get("customerEmail")));
    var all = get("/api/v1/admin/orders?size=5", admin).getBody();
    assertThat((List<?>) all.get("items")).isNotEmpty();
    assertThat(get("/api/v1/admin/orders/" + id, admin).getBody()).containsEntry("customerEmail", email).containsEntry("id", id);
    assertThat(get("/api/v1/admin/orders/" + UUID.randomUUID(), admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "NOT_A_STATUS")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void dashboardCountsTodaysOrders() {
    String admin = adminToken();
    long before = ((Number) get("/api/v1/admin/dashboard", admin).getBody().get("ordersToday")).longValue();
    String t = token(freshEmail());
    place(t, cart(product(t, "Potato Chips"), 1, Map.of()));
    var d = get("/api/v1/admin/dashboard", admin).getBody();
    assertThat(((Number) d.get("ordersToday")).longValue()).isEqualTo(before + 1);
    assertThat(((Number) d.get("salesTodayPaise")).longValue()).isPositive();
    assertThat((List<?>) d.get("last7Days")).hasSize(7);
    assertThat(d).containsKeys("newOrders", "coinsIssuedToday", "lowStock");
  }

  @Test
  void notificationsAreWrittenForCustomerAndAdminWhenAnOrderIsPlaced() throws Exception {
    String email = freshEmail();
    String t = token(email);
    UUID uid = UUID.fromString((String) get("/api/v1/me", t).getBody().get("id"));
    place(t, cart(product(t, "Potato Chips"), 1, Map.of()));
    // the outbox relay runs every two seconds
    for (int i = 0; i < 40; i++) {
      Integer n = jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ?", Integer.class, uid);
      if (n != null && n > 0) break;
      Thread.sleep(250);
    }
    assertThat(jdbc.queryForList("SELECT title FROM notification WHERE user_id = ?", String.class, uid)).contains("Order placed");
  }
}
