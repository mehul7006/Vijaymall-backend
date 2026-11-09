package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Order status freedom, staff access, reorder, totals shown the same everywhere, variants and live-update access. */
@SuppressWarnings({"rawtypes", "unchecked"})
class OrderDefectsIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  @BeforeEach
  void stock() { restock("Basmati Rice", 80); restock("Potato Chips", 80); }

  private ResponseEntity<Map> placeCod(String t, Object pid, int qty) {
    Map<String, Object> cart = new HashMap<>(Map.of("items", List.of(Map.of("productId", pid, "qty", qty)),
        "coinMode", "NONE", "fulfilment", "PICKUP"));
    long total = ((Number) post("/api/v1/cart/quote", t, cart).getBody().get("totalPaise")).longValue();
    cart.put("paymentMethod", "COD");
    cart.put("expectedTotalPaise", total);
    return call(HttpMethod.POST, "/api/v1/orders", t, cart, UUID.randomUUID().toString());
  }

  private ResponseEntity<Map> status(String t, String id, String s) {
    return post("/api/v1/admin/orders/" + id + "/status", t, Map.of("status", s));
  }

  @Test
  void anyStatusIsAllowedUntilTheOrderIsDelivered() {
    String admin = adminToken();
    String t = token(freshEmail());
    String id = (String) placeCod(t, productByName(t, "Potato Chips").get("id"), 1).getBody().get("id");
    assertThat(status(admin, id, "PACKED").getStatusCode()).as("skip ahead").isEqualTo(HttpStatus.OK);
    assertThat(status(admin, id, "CONFIRMED").getStatusCode()).as("step back").isEqualTo(HttpStatus.OK);
    assertThat(status(admin, id, "DELIVERED").getStatusCode()).as("not for a pickup order").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(status(admin, id, "PICKED_UP").getStatusCode()).isEqualTo(HttpStatus.OK);
    var after = status(admin, id, "PACKED");
    assertThat(after.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(after.getBody().get("code")).isEqualTo("ILLEGAL_TRANSITION");
  }

  @Test
  void aCashierCanListAndConfirmOrdersButStillCannotUseAdminOnlyPages() {
    String t = token(freshEmail());
    String id = (String) placeCod(t, productByName(t, "Potato Chips").get("id"), 1).getBody().get("id");
    String cashier = token(CASHIER);
    assertThat(get("/api/v1/admin/orders", cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/admin/orders/" + id, cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(status(cashier, id, "CONFIRMED").getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/admin/reports/summary", cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/orders", t).getStatusCode()).as("customers never").isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void reorderSaysWhichLinesCanBeBoughtAgain() {
    String t = token(freshEmail());
    Object pid = productByName(t, "Potato Chips").get("id");
    String id = (String) placeCod(t, pid, 2).getBody().get("id");
    var ok = get("/api/v1/orders/" + id + "/reorder", t).getBody();
    assertThat(ok).containsEntry("allAvailable", true);
    assertThat((List<Map>) ok.get("items")).singleElement().satisfies(l -> {
      assertThat(l.get("productId")).isEqualTo(pid);
      assertThat(l.get("qty")).isEqualTo(2);
      assertThat(l.get("available")).isEqualTo(true);
    });
    assertThat(get("/api/v1/orders/" + id + "/reorder", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void listAndDetailShowTheSameMoneyBreakdownToCustomerAndAdmin() {
    String admin = adminToken();
    String t = token(freshEmail());
    String id = (String) placeCod(t, productByName(t, "Basmati Rice").get("id"), 3).getBody().get("id");
    var detail = get("/api/v1/orders/" + id, t).getBody();
    var mine = ((List<Map>) get("/api/v1/orders", t).getBody().get("items")).stream().filter(o -> id.equals(o.get("id"))).findFirst().orElseThrow();
    var adminRow = ((List<Map>) get("/api/v1/admin/orders", admin).getBody().get("items")).stream().filter(o -> id.equals(o.get("id"))).findFirst().orElseThrow();
    var adminDetail = get("/api/v1/admin/orders/" + id, admin).getBody();
    for (String key : List.of("subtotalPaise", "couponDiscountPaise", "coinDiscountPaise", "deliveryFeePaise", "totalPaise")) {
      assertThat(mine.get(key)).as("customer list " + key).isEqualTo(detail.get(key));
      assertThat(adminRow.get(key)).as("admin list " + key).isEqualTo(detail.get(key));
      assertThat(adminDetail.get(key)).as("admin detail " + key).isEqualTo(detail.get(key));
    }
  }

  @Test
  void productsAreGroupedWithTheirVariants() {
    String admin = adminToken();
    String t = token(freshEmail());
    var base = productByName(t, "Basmati Rice");
    var added = post("/api/v1/admin/products/" + base.get("id") + "/variants", admin,
        Map.of("variantName", "5 kg", "packSize", "5", "unit", "kg", "mrpPaise", 60000, "pricePaise", 55000, "qtyOnHand", 10));
    assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var group = get("/api/v1/product-groups/" + base.get("id"), null).getBody();
    assertThat((List<Map>) group.get("variants")).extracting(v -> v.get("id")).contains(base.get("id"), added.getBody().get("id"));
    assertThat(get("/api/v1/product-groups/" + added.getBody().get("id") + "/reviews", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/product-groups?q=Basmati", null).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void liveOrderStreamNeedsASignedInUser() {
    assertThat(get("/api/v1/orders/stream", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/v1/admin/orders/stream", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void soldOutProductsAreHiddenFromShopListsButStillOpenByIdAndOnRequest() {
    String t = token(freshEmail());
    var chips = productByName(t, "Potato Chips");
    restock("Potato Chips", 0);
    jdbc.update("update inventory set qty_on_hand = 0 where product_id = ?::uuid", chips.get("id").toString());
    var list = (List<Map>) get("/api/v1/products?q=Potato&size=50", t).getBody().get("items");
    assertThat(list).extracting(p -> p.get("id")).doesNotContain(chips.get("id"));
    var groups = (List<Map>) get("/api/v1/product-groups?q=Potato&size=50", null).getBody().get("items");
    assertThat(groups).extracting(g -> g.get("defaultVariantId")).doesNotContain(chips.get("id"));
    var all = (List<Map>) get("/api/v1/products?q=Potato&size=50&includeOutOfStock=true", t).getBody().get("items");
    assertThat(all).extracting(p -> p.get("id")).contains(chips.get("id"));
    assertThat(get("/api/v1/products/" + chips.get("id"), t).getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
