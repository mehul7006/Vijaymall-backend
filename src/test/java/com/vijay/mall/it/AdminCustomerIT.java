package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The admin finds customers, sees their standing, blocks them, and corrects their coins. */
@SuppressWarnings({"unchecked", "rawtypes"})
class AdminCustomerIT extends IntegrationTestBase {

  private String idOf(String token) { return (String) get("/api/v1/me", token).getBody().get("id"); }

  @Test
  void aCustomerCanBeFoundByPartOfTheirEmailAndSeenInFull() {
    String admin = adminToken();
    String email = "findme-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    String token = token(email);
    String id = idOf(token);
    call(HttpMethod.PUT, "/api/v1/me", token, Map.of("name", "Findable Asha"), null);

    var byEmail = (List<Map>) get("/api/v1/admin/customers?q=" + email.substring(0, 14), admin).getBody().get("items");
    assertThat(byEmail).hasSize(1);
    var byName = (List<Map>) get("/api/v1/admin/customers?q=findable", admin).getBody().get("items");
    assertThat(byName).extracting(m -> m.get("id")).contains(id);

    assertThat(byEmail.get(0)).containsEntry("email", email).containsEntry("active", true).doesNotContainKey("coinBalance");
    var detail = get("/api/v1/admin/customers/" + id, admin).getBody();
    assertThat(detail).containsEntry("email", email).containsEntry("active", true).containsEntry("coinBalance", 0)
        .containsEntry("creditBalancePaise", 0).containsEntry("openOrders", 0);
    assertThat(((List<Map>) get("/api/v1/admin/customers?size=50", admin).getBody().get("items"))).isNotEmpty();
    assertThat(get("/api/v1/admin/customers/" + UUID.randomUUID(), admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/customers/" + idOf(admin), admin).getStatusCode()).as("staff are not customers").isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blockingSignsTheCustomerOutEverywhereAndUnblockingLetsThemBackIn() {
    String admin = adminToken();
    String email = freshEmail();
    var login = login(email);
    String id = idOf((String) login.get("accessToken"));

    var blocked = call(HttpMethod.PATCH, "/api/v1/admin/customers/" + id, admin, Map.of("active", false), null);
    assertThat(blocked.getBody()).containsEntry("active", false);
    var refresh = post("/api/v1/auth/refresh", null, Map.of("refreshToken", login.get("refreshToken")));
    assertThat(refresh.getStatusCode().is4xxClientError()).as("the old sign-in no longer renews").isTrue();
    post("/api/v1/auth/otp/send", null, Map.of("email", email));
    assertThat(post("/api/v1/auth/otp/verify", null, Map.of("email", email, "otp", "12345", "deviceId", "d")).getStatusCode())
        .as("and a new one is refused").isEqualTo(HttpStatus.FORBIDDEN);

    var back = call(HttpMethod.PATCH, "/api/v1/admin/customers/" + id, admin, Map.of("active", true), null);
    assertThat(back.getBody()).containsEntry("active", true);
    assertThat(login(email).get("accessToken")).isNotNull();
  }

  @Test
  void coinsCanBeGivenAndTakenBackWithAReasonAndNeverBelowZero() {
    String admin = adminToken();
    String id = idOf(token(freshEmail()));

    var gift = post("/api/v1/admin/customers/" + id + "/coins", admin, Map.of("delta", 50, "reason", "sorry for the late delivery"));
    assertThat(gift.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(gift.getBody()).containsEntry("coinBalance", 50);

    var back = post("/api/v1/admin/customers/" + id + "/coins", admin, Map.of("delta", -20, "reason", "given twice by mistake"));
    assertThat(back.getBody()).containsEntry("coinBalance", 30);
    assertThat(post("/api/v1/admin/customers/" + id + "/coins", admin, Map.of("delta", -31, "reason", "too much")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/customers/" + id + "/coins", admin, Map.of("delta", 0, "reason", "nothing")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/customers/" + id + "/coins", admin, Map.of("delta", 5, "reason", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/customers/" + id + "/coins", admin, Map.of("delta", 100001, "reason", "x")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    var audit = (List<Map>) get("/api/v1/admin/audit-log?action=COINS_ADJUST&entityId=" + id, admin).getBody().get("items");
    assertThat(audit).hasSize(2);
    assertThat(audit.get(0).get("change").toString()).contains("given twice by mistake");
  }

  @Test
  void onlyTheAdminMayManageCustomersAndTheLowStockListIsAdminOnlyToo() {
    String cashier = token("cashier@vijaysupermall.local");
    assertThat(get("/api/v1/admin/customers", cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/customers", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/stock/low", cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void theLowStockListShowsActiveProductsAtOrBelowTheirLevelEmptiestFirst() {
    String admin = adminToken();
    Object pid = productByName(admin, "Toothpaste").get("id");
    var before = jdbc.queryForMap("select qty_on_hand, low_threshold from inventory where product_id = ?::uuid", pid.toString());
    jdbc.update("update inventory set qty_on_hand = 0, low_threshold = 5 where product_id = ?::uuid", pid.toString());
    var low = (List<Map>) get("/api/v1/admin/stock/low?limit=200", admin).getBody().get("_array");
    assertThat(low.get(0).get("qty")).as("emptiest first").isEqualTo(0);
    assertThat(low).anySatisfy(i -> assertThat(i.get("name")).isEqualTo("Toothpaste"));
    assertThat(((List<Map>) get("/api/v1/admin/stock/low?limit=1", admin).getBody().get("_array"))).hasSize(1);
    assertThat(get("/api/v1/admin/stock/low?limit=-4", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
    // put back exactly what was there: other tests rely on the seeded stock of this product
    jdbc.update("update inventory set qty_on_hand = ?, low_threshold = ? where product_id = ?::uuid", before.get("qty_on_hand"), before.get("low_threshold"),
        pid.toString());
  }
}
