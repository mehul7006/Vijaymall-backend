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

/** Manual discounts and the manager's approval PIN, and the loss-prevention report, against real PostgreSQL. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PosDiscountIT extends IntegrationTestBase {
  private static final String PIN = "4821";

  @BeforeEach
  void ready() {
    restock("Basmati Rice", 100);
    var set = call(HttpMethod.PUT, "/api/v1/admin/me/approval-pin", adminToken(), Map.of("pin", PIN), null);
    assertThat(set.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  private String newCashier() {
    String email = "disc-" + UUID.randomUUID() + "@shop.test";
    post("/api/v1/admin/staff", adminToken(), Map.of("email", email, "name", "Discount tester"));
    return token(email);
  }

  private Map cart(String token) {
    List<Map> products = (List<Map>) get("/api/v1/products?size=50&includeOutOfStock=true", token).getBody().get("items");
    Object rice = products.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize")))
        .findFirst().orElseThrow().get("id");
    return Map.of("items", List.of(Map.of("productId", rice, "qty", 2)));
  }

  private long total(String token, Map cart) {
    return ((Number) post("/api/v1/pos/quote", token, cart).getBody().get("totalPaise")).longValue();
  }

  private org.springframework.http.ResponseEntity<Map> sell(String token, Map cart, long pay, Map discount) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("cart", cart);
    body.put("payments", List.of(Map.of("mode", "CASH", "amountPaise", pay)));
    if (discount != null) body.put("manualDiscount", discount);
    return call(HttpMethod.POST, "/api/v1/pos/sales", token, body, UUID.randomUUID().toString());
  }

  @Test
  void aCashierNeedsAManagersPinForADiscountAndTheBillShowsWhoApprovedIt() {
    String cashier = newCashier();
    Map cart = cart(cashier);
    long full = total(cashier, cart);

    var none = sell(cashier, cart, full - 1000, Map.of("amountPaise", 1000, "reason", "damaged pack"));
    assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(none.getBody().get("code")).isEqualTo("DISCOUNT_NEEDS_APPROVAL");

    var wrong = sell(cashier, cart, full - 1000, Map.of("amountPaise", 1000, "reason", "damaged pack", "approverPin", "0000"));
    assertThat(wrong.getBody().get("code")).isEqualTo("DISCOUNT_NEEDS_APPROVAL");

    var ok = sell(cashier, cart, full - 1000, Map.of("amountPaise", 1000, "reason", "damaged pack", "approverPin", PIN));
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(((Number) ok.getBody().get("totalPaise")).longValue()).isEqualTo(full - 1000);
    assertThat(((Number) ok.getBody().get("manualDiscountPaise")).longValue()).isEqualTo(1000);
    assertThat(ok.getBody().get("manualDiscountReason")).isEqualTo("damaged pack");
    assertThat(ok.getBody().get("manualDiscountApprover")).isNotNull();

    var receipt = get("/api/v1/pos/sales/" + ok.getBody().get("id") + "/receipt", cashier).getBody();
    assertThat((String) receipt.get("text")).contains("Discount");
  }

  @Test
  void anAdminGivesADiscountWithoutAPinAndBadDiscountsAreRefused() {
    String admin = adminToken();
    Map cart = cart(admin);
    long full = total(admin, cart);
    var ok = sell(admin, cart, full - 500, Map.of("amountPaise", 500, "reason", "owner's call"));
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    assertThat(sell(admin, cart, full, Map.of("amountPaise", 500, "reason", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(sell(admin, cart, 0, Map.of("amountPaise", full + 1, "reason", "too much")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(sell(admin, cart, full + 500, Map.of("amountPaise", -500, "reason", "negative")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void guessingThePinIsSlowedDown() {
    String cashier = newCashier();
    Map cart = cart(cashier);
    long full = total(cashier, cart);
    HttpStatus last = null;
    for (int i = 0; i < 7; i++) {
      var r = sell(cashier, cart, full - 100, Map.of("amountPaise", 100, "reason", "try", "approverPin", String.format("%04d", i)));
      last = HttpStatus.valueOf(r.getStatusCode().value());
    }
    assertThat(last).as("after 5 tries in 10 minutes").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void onlyAnAdminSetsAPinAndItMustBeFourToEightDigits() {
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/me/approval-pin", newCashier(), Map.of("pin", "1234"), null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    String admin = adminToken();
    for (String bad : List.of("123", "123456789", "12a4", " ")) {
      assertThat(call(HttpMethod.PUT, "/api/v1/admin/me/approval-pin", admin, Map.of("pin", bad), null).getStatusCode().is4xxClientError())
          .as(bad).isTrue();
    }
  }

  @Test
  void theControlsReportShowsWhoDiscountsAndRefunds() {
    String cashier = newCashier();
    Map cart = cart(cashier);
    long full = total(cashier, cart);
    var sale = sell(cashier, cart, full - 1000, Map.of("amountPaise", 1000, "reason", "report test", "approverPin", PIN));
    assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    var report = get("/api/v1/admin/reports/counter-controls?period=week", adminToken());
    assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map> rows = (List<Map>) report.getBody().get("cashiers");
    Map mine = rows.stream().filter(r -> sale.getBody().get("cashierId").equals(r.get("cashierId"))).findFirst().orElseThrow();
    assertThat(((Number) mine.get("manualDiscounts")).longValue()).isEqualTo(1);
    assertThat(((Number) mine.get("manualDiscountPaise")).longValue()).isEqualTo(1000);
    assertThat(((Number) mine.get("discountPercentOfSales")).doubleValue()).isGreaterThan(0.0);
    assertThat(get("/api/v1/admin/reports/counter-controls?period=year", adminToken()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/reports/counter-controls", cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
