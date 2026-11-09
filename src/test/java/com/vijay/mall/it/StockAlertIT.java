package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Staff are told through the real outbox when a confirmed order or a counter sale pushes a product low. */
class StockAlertIT extends IntegrationTestBase {

  @SuppressWarnings({"unchecked", "rawtypes"})
  private boolean adminHasAlertAbout(String admin, String productName) {
    Object items = get("/api/v1/me/notifications?size=50", admin).getBody().get("items");
    return ((List<Map>) items).stream().anyMatch(n -> "STOCK".equals(n.get("kind"))
        && String.valueOf(n.get("body")).contains(productName + ":"));
  }

  @Test
  void confirmingAnOrderThatDrainsAProductAlertsStaffOnce() {
    String admin = adminToken();
    String cust = token(freshEmail());
    Object pid = productByName(admin, "Bath Soap").get("id");
    jdbc.update("update inventory set qty_on_hand = 6, low_threshold = 5 where product_id = ?::uuid", pid.toString());

    var items = List.of(Map.of("productId", pid, "qty", 2));
    Map<String, Object> req = Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP");
    long total = ((Number) post("/api/v1/cart/quote", cust, req).getBody().get("totalPaise")).longValue();
    String id = (String) call(HttpMethod.POST, "/api/v1/orders", cust,
        Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP", "paymentMethod", "COD", "expectedTotalPaise", total),
        UUID.randomUUID().toString()).getBody().get("id");

    assertThat(adminHasAlertAbout(admin, "Bath Soap")).as("placing alone takes no stock, so no alert").isFalse();
    post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CONFIRMED")); // 6 -> 4, level 5
    Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(
        () -> assertThat(adminHasAlertAbout(admin, "Bath Soap")).isTrue());
    restock("Bath Soap", 50);
  }
}
