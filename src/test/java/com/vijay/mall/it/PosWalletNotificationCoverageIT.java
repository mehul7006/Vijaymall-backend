package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.notification.infra.NotificationRepository;
import com.vijay.mall.support.IntegrationTestBase;
import com.vijay.mall.wallet.infra.WalletRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Controller paths of the counter, credit, wallet and inbox modules that the flow tests never touch. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PosWalletNotificationCoverageIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  @Autowired NotificationRepository notifications;
  @Autowired WalletRepository walletRepo;

  private String mobile() { return "96" + String.format("%08d", (long) (Math.random() * 100_000_000L)); }

  private Object riceId(String token) {
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", token)
        .getBody().get("items");
    return items.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst()
        .orElseThrow().get("id");
  }

  @Test void adminCanReadAndResaveACounterProductsAttributes() {
    String admin = adminToken();
    Object rice = riceId(admin);
    Map before = get("/api/v1/admin/products/" + rice + "/pos", admin).getBody();
    assertThat(before.get("soldBy")).isEqualTo("UNIT");
    Map body = new HashMap();
    body.put("soldBy", before.get("soldBy"));
    body.put("plu", before.get("plu"));
    body.put("gstPercent", before.get("gstPercent"));
    body.put("hsn", before.get("hsn"));
    body.put("barcodes", before.get("barcodes"));
    var saved = call(HttpMethod.PUT, "/api/v1/admin/products/" + rice + "/pos", admin, body, null);
    assertThat(saved.getStatusCode()).as(String.valueOf(saved.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(saved.getBody().get("soldBy")).isEqualTo(before.get("soldBy"));
    assertThat(saved.getBody().get("gstPercent")).isEqualTo(before.get("gstPercent"));
    assertThat(saved.getBody().get("barcodes")).isEqualTo(before.get("barcodes"));
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/products/" + rice + "/pos", token(CASHIER), body, null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test void adminReadsACustomersCreditAccountAndLedger_andACashierSeesABillById() {
    restock("Basmati Rice", 20);
    String admin = adminToken(), cashier = token(CASHIER);
    String m = mobile();
    var approved = call(HttpMethod.PUT, "/api/v1/admin/credit/limit", admin, Map.of("mobile", m, "limitPaise", 40000), null);
    assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
    Object customer = approved.getBody().get("customerId");

    Map cart = Map.of("items", List.of(Map.of("productId", riceId(cashier), "qty", 1)), "customerMobile", m);
    var sale = call(HttpMethod.POST, "/api/v1/pos/sales", cashier,
        Map.of("cart", cart, "payments", List.of(Map.of("mode", "CREDIT", "amountPaise", 12500))), UUID.randomUUID().toString());
    assertThat(sale.getStatusCode()).as(String.valueOf(sale.getBody())).isEqualTo(HttpStatus.CREATED);

    var account = get("/api/v1/admin/credit/accounts/" + customer, admin);
    assertThat(account.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) account.getBody().get("balancePaise")).longValue()).isEqualTo(12500);
    assertThat(((Number) account.getBody().get("availablePaise")).longValue()).isEqualTo(27500);

    var ledger = get("/api/v1/admin/credit/accounts/" + customer + "/ledger?page=0&size=5", admin);
    assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map> entries = (List<Map>) ledger.getBody().get("items");
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("kind")).isEqualTo("SALE");
    assertThat(((Number) entries.get(0).get("deltaPaise")).longValue()).isEqualTo(12500);

    var byId = get("/api/v1/pos/sales/" + sale.getBody().get("id"), cashier);
    assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(byId.getBody().get("invoiceNo")).isEqualTo(sale.getBody().get("invoiceNo"));
    assertThat(get("/api/v1/pos/sales/" + UUID.randomUUID(), cashier).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    // the same bill is found by the customer's mobile and by today's date; an unknown mobile finds nothing
    List<Map<String, Object>> byMobile = getList("/api/v1/pos/sales?mobile=" + m, cashier);
    assertThat(byMobile).extracting(s -> s.get("id")).containsExactly(sale.getBody().get("id"));
    String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
    assertThat(getList("/api/v1/pos/sales?date=" + today, cashier)).anyMatch(s -> sale.getBody().get("id").equals(s.get("id")));
    assertThat(getList("/api/v1/pos/sales?mobile=" + mobile(), cashier)).isEmpty();
    assertThat(get("/api/v1/admin/credit/accounts/" + customer, cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test void aBillCanBeHeldAndDiscarded_andDiscardingAGhostIsNotFound() {
    String cashier = token(CASHIER);
    var held = post("/api/v1/pos/holds", cashier, Map.of("label", "tea break",
        "cart", Map.of("items", List.of(Map.of("productId", riceId(cashier), "qty", 1)))));
    assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Object id = held.getBody().get("id");
    assertThat(call(HttpMethod.DELETE, "/api/v1/pos/holds/" + id, cashier, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(call(HttpMethod.DELETE, "/api/v1/pos/holds/" + id, cashier, null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test void closingADrawerThatIsOver_needsANoteToo() {
    String email = "over-" + UUID.randomUUID() + "@shop.test";
    post("/api/v1/admin/staff", adminToken(), Map.of("email", email, "name", "Over test"));
    String cashier = token(email);
    assertThat(post("/api/v1/pos/shifts/open", cashier, Map.of("openingFloatPaise", 10000)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var over = post("/api/v1/pos/shifts/close", cashier, Map.of("countedCashPaise", 10700));
    assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(over.getBody().get("code")).isEqualTo("VARIANCE_NEEDS_NOTE");
    assertThat((String) over.getBody().get("message")).contains("over by Rs 7");
    var closed = post("/api/v1/pos/shifts/close", cashier, Map.of("countedCashPaise", 10700, "note", "customer left a tip"));
    assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) closed.getBody().get("variancePaise")).longValue()).isEqualTo(700);
  }

  @Test void coinRuleCreatedAsExplicitlyActive_staysActive() {
    String admin = adminToken();
    var created = post("/api/v1/admin/coin-rules", admin, Map.of("name", "Rule-" + UUID.randomUUID(), "spendPaise", 50000,
        "coins", 3, "mode", "PRO_RATA", "active", true));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).containsEntry("active", true).containsEntry("coins", 3);
    call(HttpMethod.DELETE, "/api/v1/admin/coin-rules/" + created.getBody().get("id"), admin, null, null);
  }

  @Test void walletLedgerShowsRowsThatBelongToNoOrder() {
    String email = freshEmail();
    Map<String, Object> auth = login(email);
    String token = (String) auth.get("accessToken");
    UUID user = UUID.fromString((String) ((Map) auth.get("user")).get("id"));
    walletRepo.insertLedger(UUID.randomUUID(), user, "ADJUST", 0, null, null, null, null, "manual:" + UUID.randomUUID(), Instant.now());
    var ledger = get("/api/v1/wallet/ledger", token);
    assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map> rows = (List<Map>) ledger.getBody().get("items");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("kind")).isEqualTo("ADJUST");
    assertThat(rows.get(0).get("orderNo")).isNull();
  }

  @Test void inboxNotificationCanBeMarkedRead_butNotSomeoneElses() {
    Map<String, Object> auth = login(freshEmail());
    String token = (String) auth.get("accessToken");
    UUID user = UUID.fromString((String) ((Map) auth.get("user")).get("id"));
    notifications.insert(user, "Hello", "Body", "ORDER", "VM-1", Instant.now());
    List<Map> items = (List<Map>) get("/api/v1/me/notifications", token).getBody().get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("read")).isEqualTo(false);
    Object id = items.get(0).get("id");

    String stranger = token(freshEmail());
    assertThat(post("/api/v1/me/notifications/" + id + "/read", stranger, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post("/api/v1/me/notifications/" + id + "/read", token, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(post("/api/v1/me/notifications/" + id + "/read", token, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    var after = get("/api/v1/me/notifications", token).getBody();
    assertThat(((List<Map>) after.get("items")).get(0).get("read")).isEqualTo(true);
    assertThat(((Number) after.get("unread")).intValue()).isZero();
  }
}
