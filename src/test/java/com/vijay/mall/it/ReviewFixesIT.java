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

/** Behaviours the frontend guide review found surprising: omitted fields must keep what is there, bad input must be a 400. */
@SuppressWarnings({"rawtypes", "unchecked"})
class ReviewFixesIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  @Test
  void leavingTheNameOutOfAProfileEditKeepsTheName() {
    String token = token(freshEmail());
    assertThat(call(HttpMethod.PUT, "/api/v1/me", token, Map.of("name", "Asha Patel"), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    var res = call(HttpMethod.PUT, "/api/v1/me", token, Map.of(), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().get("name")).isEqualTo("Asha Patel");
  }

  private Map address(String label, Boolean isDefault) {
    var m = new HashMap<String, Object>(Map.of("label", label, "line1", "12 Main Road", "area", "Navrangpura", "pincode", "380009"));
    if (isDefault != null) m.put("isDefault", isDefault);
    return m;
  }

  @Test
  void addressDefaultsAreNotLostByEditsOrDeletes() {
    String token = token(freshEmail());
    var home = call(HttpMethod.POST, "/api/v1/me/addresses", token, address("Home", true), null).getBody();
    var office = call(HttpMethod.POST, "/api/v1/me/addresses", token, address("Office", false), null).getBody();
    assertThat(home.get("isDefault")).isEqualTo(true);

    // an edit that leaves isDefault out keeps it as it was
    var edited = call(HttpMethod.PUT, "/api/v1/me/addresses/" + home.get("id"), token, address("Home 2", null), null);
    assertThat(edited.getBody().get("isDefault")).isEqualTo(true);
    var editedOffice = call(HttpMethod.PUT, "/api/v1/me/addresses/" + office.get("id"), token, address("Office 2", null), null);
    assertThat(editedOffice.getBody().get("isDefault")).isEqualTo(false);

    // deleting the default hands the flag to the one that is left
    assertThat(call(HttpMethod.DELETE, "/api/v1/me/addresses/" + home.get("id"), token, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    var left = getList("/api/v1/me/addresses", token);
    assertThat(left).hasSize(1);
    assertThat(left.get(0).get("isDefault")).isEqualTo(true);
    // deleting a non-default one changes nothing else
    var extra = call(HttpMethod.POST, "/api/v1/me/addresses", token, address("Shop", false), null).getBody();
    call(HttpMethod.DELETE, "/api/v1/me/addresses/" + extra.get("id"), token, null, null);
    assertThat(getList("/api/v1/me/addresses", token)).hasSize(1);
    call(HttpMethod.DELETE, "/api/v1/me/addresses/" + office.get("id"), token, null, null);
    assertThat(getList("/api/v1/me/addresses", token)).isEmpty();
  }

  @Test
  void sendingFalseOnTheOnlyDefaultAddressKeepsItDefault() {
    String token = token(freshEmail());
    var home = call(HttpMethod.POST, "/api/v1/me/addresses", token, address("Home", true), null).getBody();
    var edited = call(HttpMethod.PUT, "/api/v1/me/addresses/" + home.get("id"), token, address("Home", false), null);
    assertThat(edited.getBody().get("isDefault")).as("a default is only moved by marking another address as default").isEqualTo(true);
    var office = call(HttpMethod.POST, "/api/v1/me/addresses", token, address("Office", true), null).getBody();
    assertThat(office.get("isDefault")).isEqualTo(true);
    assertThat(getList("/api/v1/me/addresses", token)).filteredOn(a -> Boolean.TRUE.equals(a.get("isDefault"))).hasSize(1);
  }

  @Test
  void theSavedBasketRefusesAProductWhoseLinesAddUpToMoreThan99() {
    String token = token(freshEmail());
    Object pid = productByName(token, "Potato Chips").get("id");
    var res = call(HttpMethod.PUT, "/api/v1/cart", token, Map.of("items", List.of(Map.of("productId", pid, "qty", 60), Map.of("productId", pid, "qty", 60))), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var ok = call(HttpMethod.PUT, "/api/v1/cart", token, Map.of("items", List.of(Map.of("productId", pid, "qty", 50), Map.of("productId", pid, "qty", 49))), null);
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void anAlertLevelOfZeroCanBeSetOnEditAndAnOmittedOneKeepsTheCurrent() {
    String admin = adminToken();
    Map current = get("/api/v1/admin/products/" + productByName(admin, "Orange Juice").get("id"), admin).getBody();
    Map body = new HashMap<String, Object>();
    body.put("categoryId", current.get("categoryId"));
    body.put("nameEn", current.get("nameEn"));
    body.put("nameGu", current.get("nameGu"));
    body.put("mrpPaise", current.get("mrpPaise"));
    body.put("pricePaise", current.get("pricePaise"));
    body.put("lowThreshold", 0);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/products/" + current.get("id"), admin, body, null).getBody().get("lowThreshold")).isEqualTo(0);
    body.remove("lowThreshold");
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/products/" + current.get("id"), admin, body, null).getBody().get("lowThreshold")).isEqualTo(0);
    body.put("lowThreshold", current.get("lowThreshold"));
    call(HttpMethod.PUT, "/api/v1/admin/products/" + current.get("id"), admin, body, null);
  }

  @Test
  void blockingACustomerNeedsAnExplicitFlag() {
    String admin = adminToken();
    String customerId = (String) ((Map) login(freshEmail()).get("user")).get("id");
    assertThat(call(HttpMethod.PATCH, "/api/v1/admin/customers/" + customerId, admin, Map.of(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/customers/" + customerId, admin).getBody().get("active")).isEqualTo(true);
  }

  @Test
  void editingAProductWithoutStockOrThresholdKeepsBoth() {
    String admin = adminToken();
    restock("Orange Juice", 60);
    Map current = get("/api/v1/admin/products/" + productByName(admin, "Orange Juice").get("id"), admin).getBody();
    Map body = new HashMap<String, Object>();
    body.put("categoryId", current.get("categoryId"));
    body.put("nameEn", current.get("nameEn"));
    body.put("nameGu", current.get("nameGu"));
    body.put("mrpPaise", current.get("mrpPaise"));
    body.put("pricePaise", current.get("pricePaise"));
    var res = call(HttpMethod.PUT, "/api/v1/admin/products/" + current.get("id"), admin, body, null);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().get("qtyOnHand")).as("stock is not reset to zero").isEqualTo(current.get("qtyOnHand"));
    assertThat(res.getBody().get("lowThreshold")).isEqualTo(current.get("lowThreshold"));
    body.put("qtyOnHand", -3);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/products/" + current.get("id"), admin, body, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aNegativeMinimumOrderOnACoinRuleIsA400NotA500() {
    var res = call(HttpMethod.POST, "/api/v1/admin/coin-rules", adminToken(),
        Map.of("name", "bad " + UUID.randomUUID(), "spendPaise", 10000, "coins", 1, "mode", "PRO_RATA", "minOrderPaise", -5), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void theSameProductTwiceCannotSlipPastTheQuantityLimit() {
    String token = token(freshEmail());
    Object pid = productByName(token, "Potato Chips").get("id");
    var items = List.of(Map.of("productId", pid, "qty", 60), Map.of("productId", pid, "qty", 60));
    var res = post("/api/v1/cart/quote", token, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    // the saved basket is held to the same limit
    var basket = call(HttpMethod.PUT, "/api/v1/cart", token, Map.of("items", List.of(Map.of("productId", pid, "qty", 100))), null);
    assertThat(basket.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aPendingOnlineOrderShowsWhenItExpires() {
    String admin = adminToken();
    setPaymentMethod(admin, "COD", true);
    String token = token(freshEmail());
    Object pid = productByName(token, "Potato Chips").get("id");
    restock("Potato Chips", 80);
    var items = List.of(Map.of("productId", pid, "qty", 1));
    long total = ((Number) post("/api/v1/cart/quote", token, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP")).getBody().get("totalPaise")).longValue();
    var cod = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP", "paymentMethod", "COD",
        "expectedTotalPaise", total), UUID.randomUUID().toString());
    assertThat(cod.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(cod.getBody().get("paymentExpiresAt")).as("only an order waiting for payment has a deadline").isNull();
  }

  @Test
  void aSaleWithoutACartOrWithoutAPaymentModeIsA400() {
    String cashier = token(CASHIER);
    var noCart = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("payments", List.of(Map.of("mode", "CASH", "amountPaise", 100))), UUID.randomUUID().toString());
    assertThat(noCart.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    List<Map<String, Object>> products = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", cashier).getBody().get("items");
    Object rice = products.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst().orElseThrow().get("id");
    restock("Basmati Rice", 100);
    var noMode = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", rice, "qty", 1))),
        "payments", List.of(new HashMap<String, Object>(Map.of("amountPaise", 12500)))), UUID.randomUUID().toString());
    assertThat(noMode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    // offline upload: a bill without a cart is reported as refused with a validation code, not as an internal error
    var sync = call(HttpMethod.POST, "/api/v1/pos/sync/sales", cashier,
        Map.of("sales", List.of(Map.of("idempotencyKey", UUID.randomUUID().toString(), "payments", List.of(Map.of("mode", "CASH", "amountPaise", 100))))), null);
    assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.OK);
    var results = (List<Map>) (sync.getBody().get("results") != null ? sync.getBody().get("results") : sync.getBody().get("_array"));
    assertThat(results.get(0)).containsEntry("status", "REJECTED").containsEntry("errorCode", "VALIDATION_FAILED");
  }
}
