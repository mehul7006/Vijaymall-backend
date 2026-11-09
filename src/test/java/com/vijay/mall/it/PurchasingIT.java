package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Supplier deliveries and stock counts against real PostgreSQL. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PurchasingIT extends IntegrationTestBase {

  private int stockOf(Object productId) {
    return jdbc.queryForObject("select qty_on_hand from inventory where product_id = ?::uuid", Integer.class, productId.toString());
  }

  private Object soap() { return productByName(adminToken(), "Bath Soap").get("id"); }

  @Test
  void receivingADeliveryAddsStockOnceAndKeepsTheCost() {
    String admin = adminToken();
    Object pid = soap();
    int before = stockOf(pid);
    String key = UUID.randomUUID().toString();
    Map body = Map.of("supplierName", "Agro Traders", "invoiceNo", "INV-" + key.substring(0, 6), "note", "test",
        "items", List.of(Map.of("productId", pid, "qty", 24, "unitCostPaise", 3200)));

    var res = call(HttpMethod.POST, "/api/v1/admin/purchases", admin, body, key);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(((Number) res.getBody().get("totalCostPaise")).longValue()).isEqualTo(24 * 3200);
    assertThat(stockOf(pid)).isEqualTo(before + 24);

    var again = call(HttpMethod.POST, "/api/v1/admin/purchases", admin, body, key);
    assertThat(again.getBody().get("id")).as("same key, same delivery").isEqualTo(res.getBody().get("id"));
    assertThat(stockOf(pid)).as("not added twice").isEqualTo(before + 24);

    var fetched = get("/api/v1/admin/purchases/" + res.getBody().get("id"), admin);
    assertThat(((List<Map>) fetched.getBody().get("items")).get(0).get("name")).isEqualTo("Bath Soap");
    assertThat((List<Map>) get("/api/v1/admin/purchases?size=50", admin).getBody().get("items"))
        .anySatisfy(p -> assertThat(p.get("id")).isEqualTo(res.getBody().get("id")));

    var history = (List<Map>) get("/api/v1/admin/stock-movements?productId=" + pid + "&size=5", admin).getBody().get("items");
    assertThat(history.get(0)).containsEntry("reason", "PURCHASE").containsEntry("delta", 24);
  }

  @Test
  void badDeliveriesAreRefusedAndChangeNothing() {
    String admin = adminToken();
    Object pid = soap();
    int before = stockOf(pid);
    Map noSupplier = Map.of("supplierName", " ", "items", List.of(Map.of("productId", pid, "qty", 1, "unitCostPaise", 1)));
    assertThat(call(HttpMethod.POST, "/api/v1/admin/purchases", admin, noSupplier, UUID.randomUUID().toString()).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    Map ghost = Map.of("supplierName", "S", "items", List.of(Map.of("productId", UUID.randomUUID(), "qty", 1, "unitCostPaise", 1)));
    assertThat(call(HttpMethod.POST, "/api/v1/admin/purchases", admin, ghost, UUID.randomUUID().toString()).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/purchases", admin, noSupplier).getStatusCode()).as("key header is required").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(stockOf(pid)).isEqualTo(before);
    assertThat(get("/api/v1/admin/purchases/" + UUID.randomUUID(), admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void onlyTheAdminMayReceiveStockOrCount() {
    String cashier = token("cashier@vijaysupermall.local");
    assertThat(get("/api/v1/admin/purchases", cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(post("/api/v1/admin/stock-takes", cashier, Map.of("items", List.of())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void aStockCountSetsTheShelfFigureAndLeavesATrail() {
    String admin = adminToken();
    Object pid = soap();
    jdbc.update("update inventory set qty_on_hand = 40 where product_id = ?::uuid", pid.toString());

    var res = post("/api/v1/admin/stock-takes", admin, Map.of("note", "weekly count", "items", List.of(Map.of("productId", pid, "countedQty", 37))));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(stockOf(pid)).isEqualTo(37);
    assertThat((List<Map>) res.getBody().get("changes")).singleElement().satisfies(c -> {
      assertThat(c.get("before")).isEqualTo(40);
      assertThat(c.get("delta")).isEqualTo(-3);
    });
    var history = (List<Map>) get("/api/v1/admin/stock-movements?productId=" + pid + "&size=5", admin).getBody().get("items");
    assertThat(history.get(0)).containsEntry("reason", "STOCK_TAKE").containsEntry("delta", -3);

    var same = post("/api/v1/admin/stock-takes", admin, Map.of("items", List.of(Map.of("productId", pid, "countedQty", 37))));
    assertThat(same.getBody().get("unchanged")).isEqualTo(1);
    assertThat(post("/api/v1/admin/stock-takes", admin, Map.of("items", List.of(Map.of("productId", pid, "countedQty", -1)))).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    restock("Bath Soap", 50);
  }
}
