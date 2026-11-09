package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The admin switch "round counter bills to the rupee" (gst.roundOff) through quote, sale and receipt. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PosRoundOffIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  @BeforeEach
  void stock() { restock("Basmati Rice", 100); }

  @AfterEach
  void off() { setting("NONE"); }

  private void setting(String v) {
    var r = call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(Map.of("key", "gst.roundOff", "value", v))), null);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private Object riceId(String t) {
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", t).getBody().get("items");
    return items.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst().orElseThrow().get("id");
  }

  @Test
  void roundedBillsMustBePaidAtTheRoundedAmountAndShowOnTheReceipt() {
    String cashier = token(CASHIER);
    Map cart = Map.of("items", List.of(Map.of("productId", riceId(cashier), "qty", 3)));
    setting("NEAREST_RUPEE");
    var quote = post("/api/v1/pos/quote", cashier, cart).getBody();
    long total = ((Number) quote.get("totalPaise")).longValue();
    assertThat(total % 100).as("rounded to the rupee").isZero();
    assertThat(quote).containsKey("roundOffPaise");

    var res = call(HttpMethod.POST, "/api/v1/pos/sales", cashier,
        Map.of("cart", cart, "payments", List.of(Map.of("mode", "CASH", "amountPaise", total))), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(((Number) res.getBody().get("totalPaise")).longValue()).isEqualTo(total);
    long roundOff = ((Number) res.getBody().get("roundOffPaise")).longValue();
    assertThat(roundOff).isEqualTo(((Number) quote.get("roundOffPaise")).longValue());
    var receipt = get("/api/v1/pos/sales/" + res.getBody().get("id") + "/receipt?width=32", cashier);
    if (roundOff != 0) assertThat((String) receipt.getBody().get("text")).contains("Round off");

    var wrong = call(HttpMethod.POST, "/api/v1/pos/sales", cashier,
        Map.of("cart", cart, "payments", List.of(Map.of("mode", "CASH", "amountPaise", total + 1))), UUID.randomUUID().toString());
    assertThat(wrong.getStatusCode().is4xxClientError()).isTrue();
  }

  @Test
  void switchedOffTheBillIsNotRounded() {
    String cashier = token(CASHIER);
    setting("NONE");
    var quote = post("/api/v1/pos/quote", cashier, Map.of("items", List.of(Map.of("productId", riceId(cashier), "qty", 1)))).getBody();
    assertThat(((Number) quote.get("roundOffPaise")).longValue()).isZero();
  }
}
