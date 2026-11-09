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

/** Happy path against real PostgreSQL: quote, price check, idempotent order, delivery, coins arrive. */
class OrderFlowIT extends IntegrationTestBase {

  @org.junit.jupiter.api.BeforeEach
  void stock() { restock("Basmati Rice", 20); }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void customerOrdersAdminDeliversAndCoinsArrive() {
    String admin = adminToken();
    String cust = token(freshEmail());
    // the 1 kg pack: another test adds a 5 kg variant with the same name, which must not change this test's prices
    var rice = ((java.util.List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", cust).getBody().get("items")).stream()
        .filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst().orElseThrow();

    Map<String, Object> address = post("/api/v1/me/addresses", cust,
        Map.of("label", "Home", "line1", "12 Main Road", "area", "Station", "pincode", "390001", "isDefault", true)).getBody();

    var items = List.of(Map.of("productId", rice.get("id"), "qty", 10));
    Map<String, Object> req = Map.of("items", items, "coinMode", "NONE", "fulfilment", "DELIVERY");
    Map<String, Object> quote = post("/api/v1/cart/quote", cust, req).getBody();
    long total = ((Number) quote.get("totalPaise")).longValue();
    assertThat(quote.get("subtotalPaise")).isEqualTo(125000);
    assertThat(quote.get("deliveryFeePaise")).isEqualTo(0); // above the free-delivery limit
    assertThat(quote.get("coinsToEarn")).isEqualTo(12); // 1250.00 at 10 coins per 1000

    Map<String, Object> order = Map.of("items", items, "coinMode", "NONE", "fulfilment", "DELIVERY",
        "addressId", address.get("id"), "paymentMethod", "COD", "expectedTotalPaise", total);

    Map<String, Object> stale = new HashMap<>(order);
    stale.put("expectedTotalPaise", total - 1);
    var bad = call(HttpMethod.POST, "/api/v1/orders", cust, stale, UUID.randomUUID().toString());
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(bad.getBody().get("code")).isEqualTo("PRICE_CHANGED");

    String key = UUID.randomUUID().toString();
    var first = call(HttpMethod.POST, "/api/v1/orders", cust, order, key);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.getBody().get("status")).isEqualTo("PLACED");
    var again = call(HttpMethod.POST, "/api/v1/orders", cust, order, key);
    assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));

    String id = (String) first.getBody().get("id");
    List.of("CONFIRMED", "PACKED", "OUT_FOR_DELIVERY", "DELIVERED").forEach(s ->
        assertThat(post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", s)).getStatusCode())
            .as(s).isEqualTo(HttpStatus.OK));
    var skip = post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "PACKED"));
    assertThat(skip.getBody().get("code")).isEqualTo("ILLEGAL_TRANSITION");

    Map<String, Object> wallet = get("/api/v1/wallet", cust).getBody();
    assertThat(wallet.get("coins")).isEqualTo(12);
    assertThat(((Number) wallet.get("valuePaise")).longValue()).isPositive();
    assertThat(get("/api/v1/admin/dashboard", admin).getBody()).containsKey("last7Days");
  }
}
