package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Cancelling puts stock back; customers cannot touch other people's orders or cancel late. */
class OrderCancelIT extends IntegrationTestBase {

  @SuppressWarnings({"unchecked", "rawtypes"})
  private String place(String token, Object productId, int qty) {
    var items = List.of(Map.of("productId", productId, "qty", qty));
    Map<String, Object> req = Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP");
    long total = ((Number) post("/api/v1/cart/quote", token, req).getBody().get("totalPaise")).longValue();
    var res = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", items, "coinMode", "NONE",
        "fulfilment", "PICKUP", "paymentMethod", "COD", "expectedTotalPaise", total), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  private int stock(String admin, Object pid) {
    return ((Number) get("/api/v1/admin/products/" + pid, admin).getBody().get("qtyOnHand")).intValue();
  }

  @Test
  void cancelRestoresStock() {
    String admin = adminToken();
    String cust = token(freshEmail());
    Object pid = productByName(cust, "Potato Chips").get("id");
    int before = stock(admin, pid);

    String id = place(cust, pid, 2);
    assertThat(stock(admin, pid)).isEqualTo(before - 2);

    var res = post("/api/v1/orders/" + id + "/cancel", cust, null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().get("status")).isEqualTo("CANCELLED");
    assertThat(stock(admin, pid)).isEqualTo(before);
  }

  @Test
  void otherCustomerCannotSeeOrCancel() {
    String owner = token(freshEmail());
    String other = token(freshEmail());
    String id = place(owner, productByName(owner, "Digestive Biscuits").get("id"), 1);
    assertThat(get("/api/v1/orders/" + id, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post("/api/v1/orders/" + id + "/cancel", other, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void cannotCancelOncePacking() {
    String admin = adminToken();
    String cust = token(freshEmail());
    String id = place(cust, productByName(cust, "Bath Soap").get("id"), 1);
    List.of("CONFIRMED", "PACKED").forEach(s -> post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", s)));
    var res = post("/api/v1/orders/" + id + "/cancel", cust, null);
    assertThat(res.getStatusCode().is4xxClientError()).isTrue();
  }
}
