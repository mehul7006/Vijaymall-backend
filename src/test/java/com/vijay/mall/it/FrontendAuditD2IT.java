package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Frontend audit round D2 against real PostgreSQL: one active coin rule, shop settings that keep what is not sent, the barcode and
 * label settings on the config screen, the paged inbox, the credit picker names and the push error texts.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class FrontendAuditD2IT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  private Map<String, Object> rule(String name, boolean active) {
    return Map.of("name", name, "spendPaise", 100000, "coins", 10, "mode", "PRO_RATA", "active", active);
  }

  private List<Map<String, Object>> activeRules() {
    return getList("/api/v1/admin/coin-rules", adminToken()).stream().filter(r -> Boolean.TRUE.equals(r.get("active"))).toList();
  }

  // ---------- N-5 ----------
  @Test
  void activatingARuleSwitchesTheOthersOffAndTheAdminListShowsIt() {
    String admin = adminToken();
    String a = (String) post("/api/v1/admin/coin-rules", admin, rule("A-" + UUID.randomUUID(), true)).getBody().get("id");
    String b = (String) post("/api/v1/admin/coin-rules", admin, rule("B-" + UUID.randomUUID(), true)).getBody().get("id");
    assertThat(activeRules()).extracting(r -> r.get("id")).containsExactly(b);

    var back = call(HttpMethod.PUT, "/api/v1/admin/coin-rules/" + a, admin, rule("A again", true), null);
    assertThat(back.getBody()).containsEntry("active", true);
    assertThat(activeRules()).extracting(r -> r.get("id")).containsExactly(a);

    String idle = (String) post("/api/v1/admin/coin-rules", admin, rule("Idle-" + UUID.randomUUID(), false)).getBody().get("id");
    assertThat(activeRules()).extracting(r -> r.get("id")).as("an inactive rule changes nothing").containsExactly(a);
    call(HttpMethod.PUT, "/api/v1/admin/coin-rules/" + a, admin, rule("A off", false), null);
    assertThat(activeRules()).as("switching the only active rule off leaves none").isEmpty();
    assertThat(idle).isNotBlank();
    assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'coin_rule_one_active'", Long.class)).isEqualTo(1);
  }

  @Test
  void twoSimultaneousActivationsLeaveExactlyOneActiveRule() throws Exception {
    String admin = adminToken();
    List<Callable<Integer>> jobs = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String name = "Race-" + i + "-" + UUID.randomUUID();
      jobs.add(() -> post("/api/v1/admin/coin-rules", admin, rule(name, true)).getStatusCode().value());
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      for (Future<Integer> f : pool.invokeAll(jobs)) assertThat(f.get()).isEqualTo(201);
    }
    assertThat(jdbc.queryForObject("select count(*) from coin_rule where active", Long.class)).isEqualTo(1);
    assertThat(activeRules()).hasSize(1);
  }

  @Test
  void theMigrationKeepsOnlyTheMostRecentlyUpdatedActiveRule() {
    jdbc.execute("drop index coin_rule_one_active");
    try {
      jdbc.update("update coin_rule set active = false");
      UUID older = UUID.randomUUID(), newer = UUID.randomUUID();
      for (UUID id : List.of(older, newer)) {
        jdbc.update("insert into coin_rule (id, name, spend_paise, coins, mode, min_order_paise, active) values (?, ?, 100, 1, 'PRO_RATA', 0, true)", id, "M-" + id);
      }
      jdbc.update("update coin_rule set updated_at = now() - interval '1 day' where id = ?", older);
      jdbc.update("""
          UPDATE coin_rule SET active = false
           WHERE active
             AND id <> (SELECT id FROM coin_rule WHERE active ORDER BY updated_at DESC, id DESC LIMIT 1)""");
      assertThat(jdbc.queryForList("select id from coin_rule where active", UUID.class)).containsExactly(newer);
    } finally {
      jdbc.update("update coin_rule set active = false");
      jdbc.execute("create unique index coin_rule_one_active on coin_rule (active) where active");
    }
  }

  // ---------- N-6 ----------
  @Test
  void shopSettingsKeepWhatIsNotSentAndAnEmptyStringClears() {
    String admin = adminToken();
    Map<String, Object> original = new HashMap<>(get("/api/v1/admin/shop-settings", admin).getBody());
    try {
      shopSettingsRules(admin);
    } finally {
      assertThat(call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, original, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
  }

  private void shopSettingsRules(String admin) {
    Map<String, Object> full = new HashMap<>(Map.of("shopName", "Vijay Super Mall", "address", "Market Road", "phone", "9876543210",
        "gstin", "24ABCDE1234F1Z5", "stateCode", "27", "upiId", "vijay@upi", "invoicePrefix", "VSM", "receiptFooter", "Thanks", "weighedPrefix", "3"));
    full.put("weighedMode", "PRICE_PAISE");
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, full, null).getStatusCode()).isEqualTo(HttpStatus.OK);

    var onlyNames = call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, Map.of("shopName", "Vijay Mall", "shopNameGu", "વિજય"), null);
    assertThat(onlyNames.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(onlyNames.getBody()).containsEntry("shopName", "Vijay Mall").containsEntry("shopNameGu", "વિજય")
        .containsEntry("address", "Market Road").containsEntry("phone", "9876543210").containsEntry("gstin", "24ABCDE1234F1Z5")
        .containsEntry("stateCode", "27").containsEntry("upiId", "vijay@upi").containsEntry("invoicePrefix", "VSM")
        .containsEntry("receiptFooter", "Thanks").containsEntry("weighedPrefix", "3").containsEntry("weighedMode", "PRICE_PAISE");
    assertThat(get("/api/v1/admin/shop-settings", admin).getBody()).containsEntry("address", "Market Road");

    Map<String, Object> cleared = new HashMap<>();
    cleared.put("shopName", "Vijay Mall");
    cleared.put("address", "");
    cleared.put("gstin", "");
    cleared.put("stateCode", "");
    var res = call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, cleared, null);
    assertThat(res.getBody()).containsEntry("address", "").containsEntry("gstin", "").containsEntry("stateCode", "24")
        .containsEntry("phone", "9876543210").containsEntry("upiId", "vijay@upi");

    assertThat(call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, Map.of("address", "x"), null).getStatusCode())
        .as("the shop name stays required").isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ---------- N-12 ----------
  @Test
  void theConfigScreenListsTheBarcodeAndLabelSettingsAndChecksThem() {
    String admin = adminToken();
    var config = get("/api/v1/admin/config", admin).getBody();
    String text = config.toString();
    for (String key : List.of("barcode.storePrefix", "barcode.variableMeasure.enabled", "barcode.variableMeasure.prefix", "label.dpi")) {
      assertThat(text).contains(key);
    }
    assertThat(text).contains("Barcodes and labels");
    for (Object[] bad : new Object[][] {{"barcode.storePrefix", "39"}, {"barcode.storePrefix", "2x"}, {"label.dpi", "250"},
        {"barcode.variableMeasure.enabled", "maybe"}, {"barcode.variableMeasure.prefix", "30"}}) {
      var res = call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(Map.of("key", bad[0], "value", bad[1]))), null);
      assertThat(res.getStatusCode()).as(bad[0] + "=" + bad[1]).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    var ok = call(HttpMethod.PUT, "/api/v1/admin/config", admin,
        Map.of("changes", List.of(Map.of("key", "label.dpi", "value", "300"), Map.of("key", "barcode.storePrefix", "value", "28"))), null);
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    call(HttpMethod.PUT, "/api/v1/admin/config", admin,
        Map.of("changes", List.of(Map.of("key", "label.dpi", "value", ""), Map.of("key", "barcode.storePrefix", "value", ""))), null);
  }

  // ---------- N-13 ----------
  @Test
  void theInboxSaysWhetherMoreFollowAndHowManyThereAre() {
    String email = freshEmail();
    String token = token(email);
    UUID user = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    for (int i = 0; i < 3; i++) {
      jdbc.update("insert into notification (id, user_id, title, body, kind, created_at) values (gen_random_uuid(), ?, 't', 'b', 'ORDER', now() - ? * interval '1 minute')", user, i);
    }
    var first = get("/api/v1/me/notifications?size=2", token).getBody();
    assertThat((List) first.get("items")).hasSize(2);
    assertThat(first).containsEntry("total", 3).containsEntry("totalExact", true).containsEntry("hasMore", true).containsEntry("unread", 3);
    var last = get("/api/v1/me/notifications?size=2&page=1", token).getBody();
    assertThat((List) last.get("items")).hasSize(1);
    assertThat(last).containsEntry("total", 3).containsEntry("totalExact", true).containsEntry("hasMore", false);
    var past = get("/api/v1/me/notifications?size=2&page=5", token).getBody();
    assertThat(past).containsEntry("total", 3).containsEntry("hasMore", false);
  }

  // ---------- N-15 ----------
  @Test
  void theCreditPickerNeverShowsAMissingNameAsNull() {
    String mobile = "96" + String.format("%08d", (long) (Math.random() * 100_000_000L));
    var limit = call(HttpMethod.PUT, "/api/v1/admin/credit/limit", adminToken(), Map.of("mobile", mobile, "limitPaise", 50000), null);
    assertThat(limit.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(jdbc.queryForObject("select name from users where mobile = ?", String.class, mobile)).isNull();
    var page = get("/api/v1/pos/credit/customers?q=" + mobile, token(CASHIER)).getBody();
    List<Map> items = (List<Map>) page.get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("name")).isEqualTo(mobile.substring(0, 2) + "XXXXXX" + mobile.substring(8));
    assertThat(items.get(0)).containsEntry("maskedMobile", mobile.substring(0, 2) + "XXXXXX" + mobile.substring(8));
  }

  // ---------- N-22 ----------
  private static Map<String, Object> fields(ResponseEntity<Map> r) {
    return (Map<String, Object>) ((Map<String, Object>) r.getBody().get("details")).get("fields");
  }

  @Test
  void pushErrorsNameTheFieldsAndTheChoices() {
    String admin = adminToken();
    var audience = post("/api/v1/admin/notifications/broadcast", admin, Map.of("audience", "NOBODY", "title", "Hi", "body", "There"));
    assertThat(audience.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(audience)).isEqualTo(Map.of("audience", "must be one of ALL, CUSTOMERS, STAFF, ADMINS, CASHIERS, USERS, SEGMENT"));
    var both = post("/api/v1/admin/notifications/segments", admin, Map.of("name", "", "rules", List.of()));
    assertThat(fields(both)).containsKeys("name", "rules");
    var bothOmitted = post("/api/v1/admin/notifications/segments", admin, Map.of());
    assertThat(fields(bothOmitted)).containsKeys("name", "rules");
    var empty = post("/api/v1/admin/notifications/segments/preview", admin, Map.of());
    assertThat(fields(empty)).isEqualTo(Map.of("segment", "give a segmentId or a segment (match and rules)"));
  }
}
