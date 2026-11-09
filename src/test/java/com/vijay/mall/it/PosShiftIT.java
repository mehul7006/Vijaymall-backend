package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Cash drawer control against real PostgreSQL: float, cash sales, a cash refund, the count and the variance. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PosShiftIT extends IntegrationTestBase {

  private String newCashier() {
    String email = "shift-" + UUID.randomUUID() + "@shop.test";
    post("/api/v1/admin/staff", adminToken(), Map.of("email", email, "name", "Shift test"));
    return token(email);
  }

  @Test
  void shiftTracksFloatCashSalesAndVariance() {
    restock("Basmati Rice", 100);
    String cashier = newCashier();
    assertThat(get("/api/v1/pos/shifts/current", cashier).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    var opened = post("/api/v1/pos/shifts/open", cashier, Map.of("openingFloatPaise", 100000));
    assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(post("/api/v1/pos/shifts/open", cashier, Map.of("openingFloatPaise", 1)).getStatusCode())
        .as("one open shift at a time").isEqualTo(HttpStatus.CONFLICT);
    assertThat(post("/api/v1/pos/shifts/open", newCashier(), Map.of("openingFloatPaise", -1)).getStatusCode().is4xxClientError()).isTrue();

    List<Map> products = (List<Map>) get("/api/v1/products?size=50&includeOutOfStock=true", cashier).getBody().get("items");
    Object rice = products.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize")))
        .findFirst().orElseThrow().get("id");
    Map cart = Map.of("items", List.of(Map.of("productId", rice, "qty", 1)));
    long total = ((Number) post("/api/v1/pos/quote", cashier, cart).getBody().get("totalPaise")).longValue();
    var sale = call(HttpMethod.POST, "/api/v1/pos/sales", cashier,
        Map.of("cart", cart, "payments", List.of(Map.of("mode", "CASH", "amountPaise", total))), UUID.randomUUID().toString());
    assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    var current = get("/api/v1/pos/shifts/current", cashier).getBody();
    assertThat(current.get("open")).isEqualTo(true);
    assertThat(((Number) current.get("expectedCashPaise")).longValue()).isEqualTo(100000 + total);

    var short_ = post("/api/v1/pos/shifts/close", cashier, Map.of("countedCashPaise", 100000 + total - 500));
    assertThat(short_.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(short_.getBody().get("code")).isEqualTo("VARIANCE_NEEDS_NOTE");

    var closed = post("/api/v1/pos/shifts/close", cashier, Map.of("countedCashPaise", 100000 + total - 500, "note", "gave Rs 5 change by mistake"));
    assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) closed.getBody().get("expectedCashPaise")).longValue()).isEqualTo(100000 + total);
    assertThat(((Number) closed.getBody().get("variancePaise")).longValue()).isEqualTo(-500);
    assertThat(closed.getBody().get("open")).isEqualTo(false);
    assertThat(get("/api/v1/pos/shifts/current", cashier).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    var all = get("/api/v1/admin/pos/shifts?size=50", adminToken());
    assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List<Map>) all.getBody().get("items")).anySatisfy(s -> assertThat(s.get("id")).isEqualTo(closed.getBody().get("id")));
    assertThat(get("/api/v1/admin/pos/shifts", cashier).getStatusCode()).as("admin only").isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void aPerfectCountNeedsNoNoteAndCustomersStayOut() {
    String cashier = newCashier();
    post("/api/v1/pos/shifts/open", cashier, Map.of("openingFloatPaise", 50000));
    assertThat(post("/api/v1/pos/shifts/close", cashier, Map.of("countedCashPaise", 50000)).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(post("/api/v1/pos/shifts/open", token(freshEmail()), Map.of("openingFloatPaise", 0)).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }
}
