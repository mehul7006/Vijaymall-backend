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

/**
 * The sixth round of frontend findings against real PostgreSQL: the order list's dates and status lists, the credit account search,
 * the cashier's credit picker, blocked versus no-limit credit customers, the plain return message and the hot-sale helpers.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class FrontendRoundSixIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  private String cashier() { return token(CASHIER); }

  private static String tag() { return UUID.randomUUID().toString().substring(0, 8); }

  private static String fieldOf(ResponseEntity<Map> r, String field) {
    return String.valueOf(((Map) ((Map) r.getBody().get("details")).get("fields")).get(field));
  }

  private UUID customer(String name, String mobile, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into users (id, email, name, mobile, role, status, created_at, updated_at) values (?, ?, ?, ?, 'CUSTOMER', ?, now(), now())",
        id, id + "@example.com", name, mobile, status);
    return id;
  }

  private String mobile() { return "96" + String.format("%08d", (long) (Math.random() * 100_000_000L)); }

  private String order(UUID user, String status, String placedAtIndia, String no) {
    jdbc.update("insert into orders (id, order_no, user_id, idem_key, fulfilment, status, subtotal_paise, total_paise, payment_method, payment_status, "
        + "placed_at, updated_at) values (gen_random_uuid(), ?, ?, ?, 'PICKUP', ?, 100, 100, 'COD', 'PENDING', ?::timestamp at time zone 'Asia/Kolkata', now())",
        no, user, UUID.randomUUID().toString(), status, placedAtIndia);
    return no;
  }

  private List<String> numbers(ResponseEntity<Map> r) {
    assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
    return ((List<Map>) r.getBody().get("items")).stream().map(m -> (String) m.get("orderNo")).toList();
  }

  // ---------- S-F1, S-F2: the staff order list ----------
  @Test
  void theOrderListFiltersByIndiaDayRangeAndStatusListAndNeedsAction() {
    String t = tag();
    UUID u = customer("Zed " + t, mobile(), "ACTIVE");
    // 2031-03-05 in India: two orders in the day, one just before midnight at the start of the next day, one the day before
    order(u, "PLACED", "2031-03-05 00:00:00", "A1-" + t);
    order(u, "DELIVERED", "2031-03-05 23:59:59", "A2-" + t);
    order(u, "CONFIRMED", "2031-03-06 00:00:00", "A3-" + t);
    order(u, "PACKED", "2031-03-04 23:59:59", "A4-" + t);
    order(u, "PAYMENT_PENDING", "2031-03-05 12:00:00", "A5-" + t);
    String admin = adminToken();

    assertThat(numbers(get("/api/v1/admin/orders?date=2031-03-05&q=-" + t, admin))).containsExactlyInAnyOrder("A1-" + t, "A2-" + t, "A5-" + t);
    var day = get("/api/v1/admin/orders?date=2031-03-05&size=2&q=-" + t, admin).getBody();
    assertThat(day).containsEntry("total", 3).containsEntry("totalExact", true).containsEntry("hasMore", true);
    assertThat(numbers(get("/api/v1/admin/orders?from=2031-03-05&to=2031-03-05&q=-" + t, admin)))
        .as("a plain date as the end covers the whole day").containsExactlyInAnyOrder("A1-" + t, "A2-" + t, "A5-" + t);
    assertThat(numbers(get("/api/v1/admin/orders?from=2031-03-05&to=2031-03-06T00:00:00&q=-" + t, admin)))
        .as("an instant as the end is excluded").containsExactlyInAnyOrder("A1-" + t, "A2-" + t, "A5-" + t);
    assertThat(numbers(get("/api/v1/admin/orders?from=2031-03-05T12:00:00Z&q=-" + t, admin)))
        .containsExactlyInAnyOrder("A2-" + t, "A3-" + t);
    assertThat(numbers(get("/api/v1/admin/orders?from=2031-03-04&to=2031-03-05&status=PLACED,CONFIRMED,PACKED&q=-" + t, admin)))
        .containsExactlyInAnyOrder("A1-" + t, "A4-" + t);
    assertThat(numbers(get("/api/v1/admin/orders?date=2031-03-05&status=delivered&q=" + t, admin))).containsExactly("A2-" + t);
    assertThat(numbers(get("/api/v1/admin/orders?from=2031-03-04&to=2031-03-06&needsAction=true&q=-" + t, admin)))
        .as("PLACED, CONFIRMED and PACKED need action; DELIVERED and PAYMENT_PENDING do not")
        .containsExactlyInAnyOrder("A1-" + t, "A3-" + t, "A4-" + t);
    assertThat(numbers(get("/api/v1/admin/orders?status=&date=2031-03-06&q=" + t, cashier()))).containsExactly("A3-" + t);
  }

  @Test
  void badDatesAndStatusesAnswerTheFieldThatIsWrong() {
    String admin = adminToken();
    var badFrom = get("/api/v1/admin/orders?from=tomorrow", admin);
    assertThat(badFrom.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fieldOf(badFrom, "from")).isEqualTo("must be a valid date");
    assertThat(fieldOf(get("/api/v1/admin/orders?to=2031-02-31", admin), "to")).isEqualTo("must be a valid date");
    assertThat(fieldOf(get("/api/v1/admin/orders?date=2031-03-05T10:00:00", admin), "date")).isEqualTo("must be a valid date");
    assertThat(fieldOf(get("/api/v1/admin/orders?from=2031-03-06&to=2031-03-05T00:00:00", admin), "to")).isEqualTo("must be after from");
    assertThat(fieldOf(get("/api/v1/admin/orders?date=2031-03-05&from=2031-03-01", admin), "date")).contains("from or to");
    var unknown = get("/api/v1/admin/orders?status=PLACED,SHIPPED", admin);
    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fieldOf(unknown, "status")).startsWith("must be one of PAYMENT_PENDING, PLACED, CONFIRMED");
    assertThat(get("/api/v1/admin/orders?status=NOPE", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var both = get("/api/v1/admin/orders?status=PLACED&needsAction=true", admin);
    assertThat(both.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fieldOf(both, "needsAction")).isEqualTo("cannot be used together with status");
  }

  @Test
  void theDashboardCountsTheOrdersThatNeedAction() {
    UUID u = customer("Dash " + tag(), mobile(), "ACTIVE");
    var before = get("/api/v1/admin/dashboard", adminToken()).getBody();
    order(u, "PACKED", "2031-04-01 10:00:00", "D1-" + tag());
    order(u, "PLACED", "2031-04-01 10:00:00", "D2-" + tag());
    order(u, "DELIVERED", "2031-04-01 10:00:00", "D3-" + tag());
    // the dashboard is remembered for a few seconds, so read the database figure the same way it does
    long expected = jdbc.queryForObject("select count(*) from orders where status in ('PLACED','CONFIRMED','PACKED','OUT_FOR_DELIVERY','READY_FOR_PICKUP')", Long.class);
    assertThat(before).containsKey("needsAction");
    assertThat(((Number) before.get("needsAction")).longValue()).isLessThanOrEqualTo(expected - 2).isGreaterThanOrEqualTo(0);
    assertThat(((Number) before.get("needsAction")).longValue()).isGreaterThanOrEqualTo(((Number) before.get("newOrders")).longValue());
  }

  // ---------- S-G1: credit accounts ----------
  private void limit(String mobile, long paise) {
    var res = call(HttpMethod.PUT, "/api/v1/admin/credit/limit", adminToken(), Map.of("mobile", mobile, "limitPaise", paise), null);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
  }

  @Test
  void theCreditAccountListSearchesByNameOrMobileAndShowsWhatIsAvailable() {
    String t = tag();
    String m1 = mobile(), m2 = mobile();
    UUID a = customer("Credit " + t + " Asha", m1, "ACTIVE");
    UUID b = customer("Credit " + t + " Bina", m2, "BLOCKED");
    limit(m1, 100000);
    limit(m2, 0);
    jdbc.update("update credit_account set balance_paise = 130000 where user_id = ?", a);
    jdbc.update("update credit_account set balance_paise = 500 where user_id = ?", b);
    String admin = adminToken();

    var all = get("/api/v1/admin/credit/accounts?q=" + t.toUpperCase() + "&size=10", admin).getBody();
    List<Map> items = (List<Map>) all.get("items");
    assertThat(items).extracting(m -> m.get("name")).containsExactly("Credit " + t + " Asha", "Credit " + t + " Bina");
    assertThat(((Number) items.get(0).get("availablePaise")).longValue()).as("over the limit: never negative").isZero();
    assertThat(items.get(0)).containsEntry("blocked", false);
    assertThat(items.get(1)).containsEntry("blocked", true).containsEntry("availablePaise", 0);
    assertThat(all).containsEntry("total", 2);

    var eligible = get("/api/v1/admin/credit/accounts?q=" + t + "&eligibleOnly=true", admin).getBody();
    assertThat((List<Map>) eligible.get("items")).extracting(m -> m.get("customerId")).containsExactly(a.toString());
    var byMobile = get("/api/v1/admin/credit/accounts?q=" + m2.substring(2, 9) + "&outstandingOnly=false", admin).getBody();
    assertThat((List<Map>) byMobile.get("items")).extracting(m -> m.get("customerId")).contains(b.toString());
    jdbc.update("update credit_account set balance_paise = 0 where user_id = ?", b);
    assertThat(((List<Map>) get("/api/v1/admin/credit/accounts?q=" + t, admin).getBody().get("items"))).hasSize(1);
    assertThat(get("/api/v1/admin/credit/accounts?q=%25&size=5", admin).getStatusCode()).as("a typed % is no wildcard and no error").isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/admin/credit/accounts?q=" + "x".repeat(101), admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/credit/accounts", cashier()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ---------- S-G2: the cashier's picker ----------
  @Test
  void aCashierCanFindCreditCustomersButNotChangeTheirLimit() {
    String t = tag();
    String m1 = mobile(), m2 = mobile();
    UUID a = customer("Pick " + t + " Asha", m1, "ACTIVE");
    customer("Pick " + t + " Bina", m2, "ACTIVE");
    limit(m1, 40000);
    jdbc.update("update credit_account set balance_paise = 10000 where user_id = ?", a);
    String c = cashier();

    var page = get("/api/v1/pos/credit/customers?q=" + t + "&size=10", c);
    assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map> items = (List<Map>) page.getBody().get("items");
    assertThat(items).hasSize(1);
    Map row = items.get(0);
    assertThat(row).containsEntry("customerId", a.toString()).containsEntry("name", "Pick " + t + " Asha").containsEntry("limitPaise", 40000)
        .containsEntry("balancePaise", 10000).containsEntry("availablePaise", 30000).containsEntry("blocked", false);
    assertThat((String) row.get("maskedMobile")).isEqualTo(m1.substring(0, 2) + "XXXXXX" + m1.substring(8));
    assertThat(row).doesNotContainKey("mobile");

    var everyone = (List<Map>) get("/api/v1/pos/credit/customers?q=" + t + "&all=true", c).getBody().get("items");
    assertThat(everyone).extracting(m -> m.get("name")).containsExactly("Pick " + t + " Asha", "Pick " + t + " Bina");
    assertThat(everyone.get(1)).containsEntry("limitPaise", 0).containsEntry("availablePaise", 0);
    var tooShort = get("/api/v1/pos/credit/customers?q=a&all=true", c);
    assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fieldOf(tooShort, "q")).contains("at least 2 characters");
    assertThat(get("/api/v1/pos/credit/customers", c).getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(call(HttpMethod.PUT, "/api/v1/admin/credit/limit", c, Map.of("mobile", m2, "limitPaise", 1000), null).getStatusCode())
        .as("the cashier cannot switch credit on").isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/pos/credit/customers", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // ---------- S-G3: blocked versus no credit ----------
  private ResponseEntity<Map> creditSale(String mobile) {
    restock("Basmati Rice", 50);
    Object rice = productByName(cashier(), "Basmati Rice").get("id");
    long price = ((Number) productByName(cashier(), "Basmati Rice").get("pricePaise")).longValue();
    Map<String, Object> cart = new HashMap<>(Map.of("items", List.of(Map.of("productId", rice, "qty", 1)), "customerMobile", mobile));
    return call(HttpMethod.POST, "/api/v1/pos/sales", cashier(),
        Map.of("cart", cart, "payments", List.of(Map.of("mode", "CREDIT", "amountPaise", price))), UUID.randomUUID().toString());
  }

  @Test
  void aBlockedCustomerAndACustomerWithoutAnyLimitAreToldApart() {
    String none = mobile(), blocked = mobile();
    customer("None " + tag(), none, "ACTIVE");
    customer("Blocked " + tag(), blocked, "ACTIVE");
    limit(blocked, 50000);

    var refused = creditSale(none);
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(refused.getBody()).containsEntry("code", "CREDIT_NOT_ALLOWED");
    assertThat(get("/api/v1/pos/credit/account?mobile=" + blocked, cashier()).getBody()).containsEntry("blocked", false);

    jdbc.update("update users set status = 'BLOCKED' where mobile = ?", blocked);
    var stopped = creditSale(blocked);
    assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(stopped.getBody()).containsEntry("code", "CUSTOMER_BLOCKED");
    assertThat((String) stopped.getBody().get("message")).isEqualTo("This customer is blocked, so credit cannot be given");
    assertThat(get("/api/v1/pos/credit/account?mobile=" + blocked, cashier()).getBody()).containsEntry("blocked", true);
  }

  // ---------- S-G4: returns with no items ----------
  @Test
  void aReturnWithoutItemsAsksToChooseOne() {
    var res = call(HttpMethod.POST, "/api/v1/pos/sales/" + UUID.randomUUID() + "/returns", cashier(),
        Map.of("items", List.of(), "refundMode", "CASH"), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fieldOf(res, "items")).isEqualTo("choose at least one item to return");
  }

  // ---------- hot sale helpers ----------
  private Map<String, Object> newProduct(String admin, String category, String name, int qty) {
    var res = post("/api/v1/admin/products", admin, new HashMap<>(Map.of("categoryId", category, "nameEn", name, "nameGu", "નામ",
        "mrpPaise", 2000, "pricePaise", 1800, "unit", "pack", "packSize", "100 g", "qtyOnHand", qty, "lowThreshold", 5)));
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  @Test
  void theAdminProductListFiltersAndSortsAndHotSaleCanBeChangedInBulk() {
    String admin = adminToken();
    String t = "Hs" + tag();
    var cat = post("/api/v1/admin/categories", admin, Map.of("nameEn", "Hot " + t, "nameGu", "શ્રેણી", "sortOrder", 97)).getBody();
    String cid = (String) cat.get("id");
    String low = (String) newProduct(admin, cid, t + " Bravo", 1).get("id");
    String full = (String) newProduct(admin, cid, t + " Alpha", 50).get("id");
    String off = (String) newProduct(admin, cid, t + " Charlie", 50).get("id");
    assertThat(call(HttpMethod.PATCH, "/api/v1/admin/products/" + off, admin, Map.of("active", false), null).getStatusCode()).isEqualTo(HttpStatus.OK);

    String base = "/api/v1/admin/products?categoryId=" + cid + "&size=10";
    assertThat(names(get(base, admin))).containsExactly(t + " Alpha", t + " Bravo", t + " Charlie");
    assertThat(names(get(base + "&lowStock=true", admin))).containsExactly(t + " Bravo");
    assertThat(names(get(base + "&active=false", admin))).containsExactly(t + " Charlie");
    assertThat(names(get(base + "&active=true&sort=lowStock", admin))).containsExactly(t + " Bravo", t + " Alpha");
    assertThat(names(get(base + "&sort=updated", admin))).first().isEqualTo(t + " Charlie");
    assertThat(get(base + "&sort=price", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fieldOf(get(base + "&sort=price", admin), "sort")).isEqualTo("must be one of name, lowStock, updated");

    long auditsBefore = jdbc.queryForObject("select count(*) from audit_log where action = 'HOT_SALE_BULK'", Long.class);
    Map<String, Object> body = Map.of("ids", List.of(low, full), "hotSale", true, "hotStart", "2031-01-01", "hotEnd", "2031-01-31T00:00:00Z");
    String key = UUID.randomUUID().toString();
    var done = call(HttpMethod.PATCH, "/api/v1/admin/products/hot-sale", admin, body, key);
    assertThat(done.getStatusCode()).as(String.valueOf(done.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(done.getBody()).containsEntry("changed", 2).containsEntry("ids", List.of(low, full));
    var again = call(HttpMethod.PATCH, "/api/v1/admin/products/hot-sale", admin, body, key);
    assertThat(again.getBody()).as("a repeat answers the first result and changes nothing more").isEqualTo(done.getBody());
    assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'HOT_SALE_BULK'", Long.class)).isEqualTo(auditsBefore + 1);
    assertThat(names(get(base + "&hotSale=true", admin))).containsExactly(t + " Alpha", t + " Bravo");
    assertThat(names(get(base + "&hotSale=false", admin))).containsExactly(t + " Charlie");
    assertThat(jdbc.queryForObject("select hot_start from product where id = ?::uuid", java.sql.Timestamp.class, low)).isNotNull();

    var reused = call(HttpMethod.PATCH, "/api/v1/admin/products/hot-sale", admin, Map.of("ids", List.of(low), "hotSale", false), key);
    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    var unknown = call(HttpMethod.PATCH, "/api/v1/admin/products/hot-sale", admin,
        Map.of("ids", List.of(low, UUID.randomUUID().toString()), "hotSale", false), null);
    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(names(get(base + "&hotSale=true", admin))).as("all or nothing").hasSize(2);

    assertThat(call(HttpMethod.PATCH, "/api/v1/admin/products/hot-sale", admin, Map.of("ids", List.of(), "hotSale", true), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PATCH, "/api/v1/admin/products/hot-sale", admin,
        Map.of("ids", List.of(low), "hotSale", true, "hotStart", "2031-02-01", "hotEnd", "2031-01-01"), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var off2 = call(HttpMethod.PATCH, "/api/v1/admin/products/hot-sale", admin, Map.of("ids", List.of(low, full), "hotSale", false), null);
    assertThat(off2.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(names(get(base + "&hotSale=true", admin))).isEmpty();
    assertThat(call(HttpMethod.PATCH, "/api/v1/admin/products/hot-sale", cashier(), body, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private List<String> names(ResponseEntity<Map> r) {
    assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
    return ((List<Map>) r.getBody().get("items")).stream().map(m -> (String) m.get("nameEn")).toList();
  }
}
