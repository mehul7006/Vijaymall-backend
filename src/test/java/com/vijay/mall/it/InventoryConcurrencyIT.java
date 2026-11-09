package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Many buyers, little stock: never oversell. */
class InventoryConcurrencyIT extends IntegrationTestBase {

  /**
   * Orders are accepted while the shelf looks full, but stock is taken when staff confirm. With 12 orders for the 3
   * toothpastes on the shelf, however many confirmations run at once, exactly 3 succeed and nothing is oversold.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void neverConfirmsMoreThanInStock() throws Exception {
    String admin = adminToken();
    var toothpaste = productByName(admin, "Toothpaste"); // seeded with 3
    String pid = (String) toothpaste.get("id");
    int stock = ((Number) get("/api/v1/admin/products/" + pid, admin).getBody().get("qtyOnHand")).intValue();

    List<String> tokens = IntStream.range(0, 12).mapToObj(_ -> token(freshEmail())).toList();
    var items = List.of(Map.of("productId", pid, "qty", 1));
    Map<String, Object> req = Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP");
    long total = ((Number) post("/api/v1/cart/quote", tokens.get(0), req).getBody().get("totalPaise")).longValue();
    Map<String, Object> order = Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP",
        "paymentMethod", "COD", "expectedTotalPaise", total);

    List<String> orderIds = tokens.stream().map(t ->
        (String) call(HttpMethod.POST, "/api/v1/orders", t, order, UUID.randomUUID().toString()).getBody().get("id")).toList();
    assertThat(get("/api/v1/admin/products/" + pid, admin).getBody().get("qtyOnHand")).as("placing takes no stock").isEqualTo(stock);

    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<HttpStatus>> results = orderIds.stream().map(id -> pool.submit(() ->
          HttpStatus.valueOf(post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CONFIRMED"))
              .getStatusCode().value()))).toList();
      long confirmed = 0;
      for (var f : results) if (f.get() == HttpStatus.OK) confirmed++;
      assertThat(confirmed).isEqualTo(stock);
    }
    assertThat(get("/api/v1/admin/products/" + pid, admin).getBody().get("qtyOnHand")).isEqualTo(0);
  }
}
