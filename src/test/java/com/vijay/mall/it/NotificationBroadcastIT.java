package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpMethod;

/**
 * Admin broadcasts end to end: role audiences, explicit lists and segments built from conditions. Customers with different
 * histories share one pincode, so every segment is combined with that pincode and selects exactly the people created here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class NotificationBroadcastIT extends IntegrationTestBase {
  private static final String BASE = "/api/v1/admin/notifications";
  private String admin;
  private final String pin = "9" + (10000 + ThreadLocalRandom.current().nextInt(89999));
  private final String coupon = "BC" + ThreadLocalRandom.current().nextInt(100000, 999999);
  private UUID p1;
  private UUID p2;
  private UUID p3;
  private final Map<String, UUID> people = new LinkedHashMap<>();

  private UUID person(String key) { return people.get(key); }

  private UUID customer(String key, String mobile) {
    String email = freshEmail();
    token(email);
    UUID id = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    jdbc.update("update users set mobile = ? where id = ?", mobile, id);
    jdbc.update("insert into address (id, user_id, label, line1, area, pincode, created_at) values (gen_random_uuid(), ?, 'Home', 'x', 'y', ?, now())", id, pin);
    people.put(key, id);
    return id;
  }

  private UUID product(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into product (id, product_group_id, category_id, name_en, name_gu, mrp_paise, price_paise, created_at, updated_at) "
        + "select ?, ?, id, ?, ?, 100, 100, now(), now() from category limit 1", id, id, name, name);
    return id;
  }

  private UUID order(UUID user, String status, long total, int daysAgo, UUID productId, String couponCode) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into orders (id, order_no, user_id, idem_key, fulfilment, status, subtotal_paise, total_paise, payment_method, payment_status, "
        + "coupon_code, placed_at, updated_at) values (?, ?, ?, ?, 'PICKUP', ?, ?, ?, 'COD', 'PENDING', ?, now() - make_interval(days => ?), now())",
        id, "BC-" + UUID.randomUUID(), user, UUID.randomUUID().toString(), status, total, total, couponCode, daysAgo);
    if (productId != null) {
      jdbc.update("insert into order_item (id, order_id, product_id, name_snapshot, unit_price_paise, qty, line_total_paise, earns_coins_snapshot, "
          + "coin_redeemable_snapshot) values (gen_random_uuid(), ?, ?, 'x', ?, 1, ?, true, true)", id, productId, total, total);
    }
    return id;
  }

  @BeforeAll
  void data() {
    admin = adminToken();
    p1 = product("BC-P1-" + pin);
    p2 = product("BC-P2-" + pin);
    p3 = product("BC-P3-" + pin);
    UUID a = customer("never", "7" + pin + "000");
    UUID b = customer("big", "7" + pin + "001");
    UUID c = customer("cancelled", "7" + pin + "002");
    UUID d = customer("lapsed", "7" + pin + "003");
    UUID e = customer("cart", "7" + pin + "004");
    UUID f = customer("credit", "7" + pin + "005");
    UUID g = customer("pos", "7" + pin + "006");
    UUID h = customer("blocked", "7" + pin + "007");
    order(b, "DELIVERED", 300000, 45, p1, null);
    order(b, "PLACED", 300000, 3, p2, null);
    order(c, "CANCELLED", 900000, 2, p1, null);
    order(d, "DELIVERED", 10000, 60, null, coupon);
    jdbc.update("insert into cart_item (user_id, product_id, qty, position, updated_at) values (?, ?, 1, 0, now() - interval '48 hours')", e, p3);
    jdbc.update("insert into credit_account (user_id, balance_paise, limit_paise, updated_at) values (?, 5000, 10000, now())", f);
    UUID cashier = jdbc.queryForObject("select id from users where role = 'ADMIN' limit 1", UUID.class);
    UUID sale = UUID.randomUUID();
    jdbc.update("insert into pos_sale (id, invoice_no, idem_key, cashier_id, customer_id, subtotal_paise, total_paise, taxable_paise, cgst_paise, "
        + "sgst_paise, created_at) values (?, ?, ?, ?, ?, 100000, 100000, 100000, 0, 0, now() - interval '1 day')",
        sale, "BCI-" + UUID.randomUUID(), UUID.randomUUID().toString(), cashier, g);
    jdbc.update("insert into pos_sale_item (id, sale_id, product_id, name, sold_by, qty, unit_price_paise, line_paise, net_paise, gst_percent, "
        + "taxable_paise, cgst_paise, sgst_paise) values (gen_random_uuid(), ?, ?, 'x', 'PIECE', 1, 100000, 100000, 100000, 0, 100000, 0, 0)", sale, p1);
    jdbc.update("insert into wishlist_item (id, user_id, product_id, created_at) values (gen_random_uuid(), ?, ?, now())", person("never"), p3);
    jdbc.update("update users set status = 'BLOCKED' where id = ?", h);
    order(h, "DELIVERED", 800000, 1, p1, null);
    jdbc.update("update users set created_at = now() - interval '100 days' where id = ?", d);
  }

  private Map<String, Object> rule(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private Map<String, Object> inPin() { return rule("type", "PINCODE_IN", "pincodes", List.of(pin)); }

  private Map<String, Object> segmentOf(String match, Map<String, Object>... rules) {
    return Map.of("match", match, "rules", List.of(rules));
  }

  private Map<String, Object> allWithPin(Map<String, Object> condition) { return segmentOf("ALL", inPin(), condition); }

  private Map preview(Map<String, Object> segment) {
    var r = post(BASE + "/segments/preview", admin, segment);
    assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
    return r.getBody();
  }

  private long previewCount(Map<String, Object> segment) { return ((Number) preview(segment).get("recipients")).longValue(); }

  private Map sendAndWait(Map<String, Object> body) {
    var r = call(HttpMethod.POST, BASE + "/broadcast", admin, body, UUID.randomUUID().toString());
    assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(202);
    String id = (String) r.getBody().get("id");
    Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(
        () -> assertThat(get(BASE + "/broadcasts/" + id, admin).getBody().get("status")).isEqualTo("DONE"));
    return get(BASE + "/broadcasts/" + id, admin).getBody();
  }

  private Set<String> reached(Object broadcastId) {
    return Set.copyOf(jdbc.queryForList("select u.id::text from notification n join users u on u.id = n.user_id where n.broadcast_id = ?::uuid", String.class, broadcastId));
  }

  private Set<String> ids(String... keys) {
    Set<String> out = new HashSet<>();
    for (String k : keys) out.add(person(k).toString());
    return out;
  }

  private Map<String, Object> announce(String audience, Map<String, Object> extra) {
    Map<String, Object> m = new LinkedHashMap<>(extra);
    m.put("audience", audience);
    m.put("title", "  Diwali offers  ");
    m.put("body", "Flat 10% off");
    m.put("ref", "offers");
    return m;
  }

  @Test
  void everyConditionTypeRunsAgainstTheRealDatabase() {
    var catalog = get(BASE + "/segments/condition-types", admin);
    List<Map> types = (List<Map>) catalog.getBody().get("_array");
    assertThat(types).isNotEmpty();
    for (Map t : types) {
      Map example = new LinkedHashMap((Map) t.get("example"));
      example.values().removeIf(v -> v == null);
      var r = post(BASE + "/segments/preview", admin, Map.of("match", "ALL", "rules", List.of(example)));
      assertThat(r.getStatusCode().value()).as(t.get("type") + " " + r.getBody()).isEqualTo(200);
    }
  }

  @Test
  void segmentsSelectExactlyTheRightCustomers() {
    assertThat(previewCount(allWithPin(rule("type", "ORDER_COUNT", "op", "EQ", "value", 0)))).as("never purchased (cancelled orders do not count)").isEqualTo(4);
    assertThat(previewCount(allWithPin(rule("type", "TOTAL_SPENT", "op", "GTE", "paise", 500000)))).isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "TOTAL_SPENT", "op", "BETWEEN", "minPaise", 50000, "maxPaise", 200000)))).as("pos only").isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "BOUGHT_PRODUCT", "productId", p1.toString())))).as("online and counter").isEqualTo(2);
    assertThat(previewCount(allWithPin(rule("type", "BOUGHT_PRODUCT", "productId", p1.toString(), "negate", true)))).as("never bought").isEqualTo(5);
    assertThat(previewCount(allWithPin(rule("type", "BOUGHT_PRODUCT", "productId", p1.toString(), "withinDays", 10)))).as("only the counter sale is recent").isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "LAST_ORDER", "notWithinDays", 30)))).as("lapsed").isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "LAST_ORDER", "withinDays", 5)))).isEqualTo(2);
    assertThat(previewCount(allWithPin(rule("type", "FIRST_ORDER_WITHIN", "days", 7)))).isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "CART_ABANDONED", "olderThanHours", 24)))).isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "CART_ABANDONED", "olderThanHours", 100)))).isZero();
    assertThat(previewCount(allWithPin(rule("type", "CREDIT_OWED", "op", "GTE", "paise", 1)))).isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "USED_COUPON", "code", coupon.toLowerCase())))).isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "REGISTERED", "beforeDays", 50)))).isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "AVG_ORDER_VALUE", "op", "GTE", "paise", 250000)))).isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "WISHLIST_HAS", "productId", p3.toString())))).isEqualTo(1);
    assertThat(previewCount(allWithPin(rule("type", "BOUGHT_CATEGORY", "categoryId",
        jdbc.queryForObject("select category_id from product where id = ?", UUID.class, p1).toString())))).isEqualTo(2);
    assertThat(previewCount(allWithPin(rule("type", "HAS_REVIEWED")))).isZero();
    assertThat(previewCount(allWithPin(rule("type", "COIN_BALANCE", "op", "EQ", "value", 0)))).isEqualTo(7);
    assertThat(previewCount(allWithPin(rule("type", "SOURCE", "source", "app")))).isEqualTo(7);
    assertThat(previewCount(allWithPin(rule("type", "HAS_DEVICE", "has", false)))).isEqualTo(7);
  }

  @Test
  void matchAnyOrsTheConditionsAndAllAndsThem() {
    var bought2 = rule("type", "BOUGHT_PRODUCT", "productId", p2.toString());
    var wished3 = rule("type", "WISHLIST_HAS", "productId", p3.toString());
    assertThat(previewCount(segmentOf("ANY", bought2, wished3))).isEqualTo(2);
    assertThat(previewCount(segmentOf("ALL", bought2, wished3))).isZero();
    Map result = preview(segmentOf("ANY", bought2, wished3));
    assertThat(result.get("totalExact")).isEqualTo(true);
    List<Map> sample = (List<Map>) result.get("sample");
    assertThat(sample).hasSize(2);
    assertThat(String.valueOf(sample.get(0).get("mobile"))).contains("X");
  }

  @Test
  void aBroadcastToASegmentReachesOnlyTheMatchingCustomersAndKeepsItsSnapshot() {
    var seg = post(BASE + "/segments", admin, Map.of("name", "Big spenders " + pin, "description", "d", "match", "ALL",
        "rules", List.of(inPin(), rule("type", "TOTAL_SPENT", "op", "GTE", "paise", 500000))));
    assertThat(seg.getStatusCode().value()).isEqualTo(201);
    String segId = (String) seg.getBody().get("id");
    assertThat(post(BASE + "/segments", admin, Map.of("name", "big SPENDERS " + pin, "match", "ALL", "rules", List.of(inPin()))).getStatusCode().value()).isEqualTo(409);
    assertThat(previewCount(Map.of("segmentId", segId))).isEqualTo(1);

    Map done = sendAndWait(announce("SEGMENT", Map.of("segmentId", segId)));
    assertThat(reached(done.get("id"))).isEqualTo(ids("big"));
    assertThat(done.get("delivered")).isEqualTo(1);
    assertThat(done.get("title")).isEqualTo("Diwali offers");

    var edit = call(HttpMethod.PUT, BASE + "/segments/" + segId, admin, Map.of("name", "Everyone here " + pin, "match", "ALL", "rules", List.of(inPin())), null);
    assertThat(edit.getStatusCode().value()).isEqualTo(200);
    assertThat(previewCount(Map.of("segmentId", segId))).isEqualTo(7);
    assertThat(call(HttpMethod.DELETE, BASE + "/segments/" + segId, admin, null, null).getStatusCode().value()).isEqualTo(204);
    assertThat(get(BASE + "/segments/" + segId, admin).getStatusCode().value()).isEqualTo(404);

    Map past = get(BASE + "/broadcasts/" + done.get("id"), admin).getBody();
    Map snapshot = (Map) past.get("segment");
    assertThat((List) snapshot.get("rules")).hasSize(2);
    assertThat(past.get("segmentId")).isEqualTo(segId);
    assertThat(reached(done.get("id"))).isEqualTo(ids("big"));
  }

  @Test
  void inlineSegmentAndExplicitListsAndTheInbox() {
    Map done = sendAndWait(announce("SEGMENT", Map.of("segment", allWithPin(rule("type", "ORDER_COUNT", "op", "EQ", "value", 0)))));
    assertThat(reached(done.get("id"))).isEqualTo(ids("never", "cancelled", "cart", "credit"));

    String email = jdbc.queryForObject("select email from users where id = ?", String.class, person("never"));
    String tok = token(email);
    var inbox = get("/api/v1/me/notifications?size=50", tok).getBody();
    List<Map> items = (List<Map>) inbox.get("items");
    String rowId = jdbc.queryForObject("select id::text from notification where broadcast_id = ?::uuid and user_id = ?", String.class, done.get("id"), person("never"));
    assertThat(items.stream().filter(n -> rowId.equals(n.get("id")) && "ANNOUNCEMENT".equals(n.get("kind")) && "offers".equals(n.get("ref")))).hasSize(1);

    UUID unknown = UUID.randomUUID();
    Map users = sendAndWait(announce("USERS", Map.of("userIds", List.of(person("big").toString(), person("blocked").toString(), unknown.toString()),
        "mobiles", List.of("7" + pin + "001", "7" + pin + "002", "0000000000"))));
    assertThat(reached(users.get("id"))).isEqualTo(ids("big", "cancelled"));
    assertThat((List<String>) users.get("skipped")).containsExactlyInAnyOrder(person("blocked").toString(), unknown.toString(), "0000000000");
    assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ? and broadcast_id is not null", Long.class, person("blocked"))).isZero();
  }

  @Test
  void roleAudiencesReachEveryoneEligibleAndNeverTheBlocked() {
    var size = get(BASE + "/audience-size?audience=CUSTOMERS", admin).getBody();
    long customers = jdbc.queryForObject("select count(*) from users where role = 'CUSTOMER' and status = 'ACTIVE' and not (source = 'POS' and not mobile_verified)", Long.class);
    assertThat(((Number) size.get("recipients")).longValue()).isEqualTo(customers);
    assertThat(get(BASE + "/audience-size?audience=USERS", admin).getStatusCode().value()).isEqualTo(400);

    Map c = sendAndWait(announce("CUSTOMERS", Map.of()));
    assertThat(reached(c.get("id"))).contains(person("never").toString(), person("big").toString()).doesNotContain(person("blocked").toString());
    assertThat(reached(c.get("id"))).hasSize((int) customers);
    Map staff = sendAndWait(announce("STAFF", Map.of()));
    assertThat(reached(staff.get("id"))).doesNotContain(person("never").toString());
    Map all = sendAndWait(announce("ALL", Map.of()));
    assertThat(reached(all.get("id"))).contains(person("never").toString()).doesNotContain(person("blocked").toString());
    for (Object id : new Object[] {c.get("id"), all.get("id")}) {
      assertThat(jdbc.queryForObject("select count(*) from (select user_id from notification where broadcast_id = ?::uuid group by user_id having count(*) > 1) d",
          Long.class, id)).isZero();
    }
  }

  @Test
  void idempotencyListingAndAccessRules() {
    String key = UUID.randomUUID().toString();
    Map<String, Object> body = announce("USERS", Map.of("userIds", List.of(person("big").toString())));
    var first = call(HttpMethod.POST, BASE + "/broadcast", admin, body, key);
    var again = call(HttpMethod.POST, BASE + "/broadcast", admin, body, key);
    assertThat(again.getStatusCode().value()).isEqualTo(202);
    assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    Map<String, Object> other = new LinkedHashMap<>(body);
    other.put("body", "changed");
    var reuse = call(HttpMethod.POST, BASE + "/broadcast", admin, other, key);
    assertThat(reuse.getStatusCode().value()).isEqualTo(409);
    assertThat(reuse.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    var bad = post(BASE + "/broadcast", admin, Map.of("audience", "ALL", "title", "  ", "body", "x".repeat(501)));
    assertThat(bad.getStatusCode().value()).isEqualTo(400);
    assertThat((Map) ((Map) bad.getBody().get("details")).get("fields")).containsKeys("title", "body");
    assertThat(post(BASE + "/broadcast", admin, Map.of("audience", "USERS", "userIds", List.of(UUID.randomUUID().toString()), "title", "t", "body", "b"))
        .getStatusCode().value()).isEqualTo(422);

    var list = get(BASE + "/broadcasts?size=5", admin).getBody();
    assertThat((List) list.get("items")).isNotEmpty();
    assertThat(get(BASE + "/broadcasts/" + UUID.randomUUID(), admin).getStatusCode().value()).isEqualTo(404);
    assertThat(get(BASE + "/segments?size=5", admin).getStatusCode().value()).isEqualTo(200);

    String customer = token(freshEmail());
    assertThat(post(BASE + "/broadcast", customer, body).getStatusCode().value()).isEqualTo(403);
    assertThat(get(BASE + "/segments/condition-types", customer).getStatusCode().value()).isEqualTo(403);
    assertThat(post(BASE + "/broadcast", null, body).getStatusCode().value()).isEqualTo(401);
  }
}
