package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** A retry or double tap of an admin action with the same Idempotency-Key must not apply it twice. */
@SuppressWarnings({"rawtypes", "unchecked"})
class AdminRepeatSafetyIT extends IntegrationTestBase {
  @org.springframework.beans.factory.annotation.Autowired com.vijay.mall.common.idem.AdminOps ops;

  @Test
  void oldKeysArePurgedAndRecentOnesKept() {
    jdbc.update("insert into admin_op (action, op_key, created_at) values ('t', 'old-key', now() - interval '40 days'), ('t', 'new-key', now())");
    ops.purge();
    assertThat(jdbc.queryForObject("select count(*) from admin_op where op_key = 'old-key'", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from admin_op where op_key = 'new-key'", Integer.class)).isEqualTo(1);
    jdbc.update("delete from admin_op where op_key = 'new-key'");
  }


  private int onHand(Object pid) {
    return jdbc.queryForObject("select qty_on_hand from inventory where product_id = ?::uuid", Integer.class, pid.toString());
  }

  private static String key() { return UUID.randomUUID().toString(); }

  @Test
  void aRestockWithTheSameKeyAddsTheStockOnce() {
    String admin = adminToken();
    Object pid = productByName(admin, "Orange Juice").get("id");
    int before = onHand(pid);
    String k = key();
    var first = call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", 50, "reason", "RESTOCK"), k);
    var again = call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", 50, "reason", "RESTOCK"), k);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(onHand(pid)).as("+50 once, not +100").isEqualTo(before + 50);
    assertThat(again.getBody().get("qtyOnHand")).isEqualTo(before + 50);

    call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", 50, "reason", "RESTOCK"), key());
    assertThat(onHand(pid)).as("a new key is a new action").isEqualTo(before + 100);
    call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", -100, "reason", "ADJUST"), null);
    call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", 1, "reason", "ADJUST"), null);
    call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", 1, "reason", "ADJUST"), null);
    assertThat(onHand(pid)).as("without a key every call applies, as before").isEqualTo(before + 2);
    restock("Orange Juice", 60);
  }

  @Test
  void aFailedActionDoesNotUseUpItsKey() {
    String admin = adminToken();
    Object pid = productByName(admin, "Orange Juice").get("id");
    String k = key();
    var bad = call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", -100000, "reason", "ADJUST"), k);
    assertThat(bad.getStatusCode().is4xxClientError()).isTrue();
    var good = call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", 1, "reason", "RESTOCK"), k);
    assertThat(good.getStatusCode()).isEqualTo(HttpStatus.OK);
    restock("Orange Juice", 60);
  }

  @Test
  void aTooLongKeyIsRefused() {
    String admin = adminToken();
    Object pid = productByName(admin, "Orange Juice").get("id");
    var res = call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", 1, "reason", "RESTOCK"), "k".repeat(101));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void coinsAreGivenOnceForOneKey() {
    String admin = adminToken();
    String email = freshEmail();
    var login = login(email);
    String customerId = (String) ((Map) login.get("user")).get("id");
    String k = key();
    for (int i = 0; i < 2; i++) {
      var r = call(HttpMethod.POST, "/api/v1/admin/customers/" + customerId + "/coins", admin, Map.of("delta", 25, "reason", "sorry for the delay"), k);
      assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    Integer coins = jdbc.queryForObject("select coalesce(sum(coins), 0) from coin_ledger where user_id = ?::uuid and kind = 'ADJUST'", Integer.class, customerId);
    assertThat(coins).isEqualTo(25);
  }

  @Test
  void aCreditWriteOffWithTheSameKeyIsAppliedOnce() {
    String admin = adminToken();
    String mobile = "98" + (10000000 + (int) (Math.random() * 89999999));
    var approve = call(HttpMethod.PUT, "/api/v1/admin/credit/limit", admin, Map.of("mobile", mobile, "limitPaise", 500000), null);
    assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);
    String customerId = (String) approve.getBody().get("customerId");
    String k = key();
    for (int i = 0; i < 2; i++) {
      var r = call(HttpMethod.POST, "/api/v1/admin/credit/accounts/" + customerId + "/adjust", admin, Map.of("amountPaise", 30000, "note", "opening balance"), k);
      assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    long balance = ((Number) get("/api/v1/admin/credit/accounts/" + customerId, admin).getBody().get("balancePaise")).longValue();
    assertThat(balance).as("owed Rs 300 once, not Rs 600").isEqualTo(30000);
  }

  @Test
  void aProductCreatedTwiceWithOneKeyIsOneProduct() {
    String admin = adminToken();
    Object category = getList("/api/v1/categories", admin).get(0).get("id");
    String name = "Repeat Safe " + System.nanoTime();
    Map body = Map.of("categoryId", category, "nameEn", name, "nameGu", name, "mrpPaise", 1000, "pricePaise", 900, "qtyOnHand", 5);
    String k = key();
    var a = call(HttpMethod.POST, "/api/v1/admin/products", admin, body, k);
    var b = call(HttpMethod.POST, "/api/v1/admin/products", admin, body, k);
    assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(b.getBody().get("id")).isEqualTo(a.getBody().get("id"));
    assertThat(jdbc.queryForObject("select count(*) from product where name_en = ?", Integer.class, name)).isEqualTo(1);
  }

  @Test
  void aCategoryCreatedTwiceWithOneKeyIsOneCategory() {
    String admin = adminToken();
    String name = "Cat " + System.nanoTime();
    String k = key();
    var a = call(HttpMethod.POST, "/api/v1/admin/categories", admin, Map.of("nameEn", name, "nameGu", name), k);
    var b = call(HttpMethod.POST, "/api/v1/admin/categories", admin, Map.of("nameEn", name, "nameGu", name), k);
    assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(b.getBody().get("id")).isEqualTo(a.getBody().get("id"));
    assertThat(jdbc.queryForObject("select count(*) from category where name_en = ?", Integer.class, name)).isEqualTo(1);
  }

  @Test
  void aCoinRuleCreatedTwiceWithOneKeyIsOneRule() {
    String admin = adminToken();
    String name = "Rule " + System.nanoTime();
    Map body = Map.of("name", name, "spendPaise", 10000, "coins", 1, "mode", "PRO_RATA", "active", false);
    String k = key();
    var a = call(HttpMethod.POST, "/api/v1/admin/coin-rules", admin, body, k);
    var b = call(HttpMethod.POST, "/api/v1/admin/coin-rules", admin, body, k);
    assertThat(a.getStatusCode().is2xxSuccessful()).as(String.valueOf(a.getBody())).isTrue();
    assertThat(b.getBody().get("id")).isEqualTo(a.getBody().get("id"));
    assertThat(jdbc.queryForObject("select count(*) from coin_rule where name = ?", Integer.class, name)).isEqualTo(1);
    jdbc.update("delete from coin_rule where name = ?", name);
  }

  @Test
  void aStockTakeRepeatedWithTheSameKeyChangesNothingTheSecondTime() {
    String admin = adminToken();
    Object pid = productByName(admin, "Orange Juice").get("id");
    String k = key();
    Map body = Map.of("note", "shelf count", "items", List.of(Map.of("productId", pid, "countedQty", 41)));
    var first = call(HttpMethod.POST, "/api/v1/admin/stock-takes", admin, body, k);
    assertThat(first.getStatusCode()).as(String.valueOf(first.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(onHand(pid)).isEqualTo(41);
    call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", -3, "reason", "ADJUST"), null); // a sale-like change in between
    var again = call(HttpMethod.POST, "/api/v1/admin/stock-takes", admin, body, k);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(again.getBody()).as("a repeat returns what the first call returned").isEqualTo(first.getBody());
    assertThat(onHand(pid)).as("the repeat did not overwrite the change made since").isEqualTo(38);
    restock("Orange Juice", 60);
  }

  @Test
  void theSameKeyForADifferentRequestIsRefusedNotSwallowed() {
    String admin = adminToken();
    Object pid = productByName(admin, "Orange Juice").get("id");
    int before = onHand(pid);
    String k = key();
    assertThat(call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", 5, "reason", "RESTOCK"), k).getStatusCode()).isEqualTo(HttpStatus.OK);
    var other = call(HttpMethod.POST, "/api/v1/admin/products/" + pid + "/stock", admin, Map.of("delta", 7, "reason", "RESTOCK"), k);
    assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(other.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    assertThat(onHand(pid)).isEqualTo(before + 5);
    restock("Orange Juice", 60);
  }

  @Test
  void aRepeatWhoseCreatedThingWasDeletedMeansTheResultIsGone() {
    String admin = adminToken();
    String name = "Rule gone " + System.nanoTime();
    Map body = Map.of("name", name, "spendPaise", 10000, "coins", 1, "mode", "PRO_RATA", "active", false);
    String k = key();
    assertThat(call(HttpMethod.POST, "/api/v1/admin/coin-rules", admin, body, k).getStatusCode().is2xxSuccessful()).isTrue();
    jdbc.update("delete from coin_rule where name = ?", name);
    var again = call(HttpMethod.POST, "/api/v1/admin/coin-rules", admin, body, k);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(again.getBody()).containsEntry("code", "IDEMPOTENT_RESULT_GONE");
  }
}
