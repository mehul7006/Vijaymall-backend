package com.vijay.mall.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.cache.AppCaches;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Helpers for the flash sale integration tests: fresh products, sale set-up, orders and counter bills. */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class FlashTestBase extends IntegrationTestBase {
  public static final String CASHIER = "cashier@vijaysupermall.local";

  @Autowired protected AppCaches caches;

  private static String categoryId;

  protected String admin() { return adminToken(); }

  protected String cashier() { return token(CASHIER); }

  protected synchronized String category() {
    if (categoryId == null) {
      var res = post("/api/v1/admin/categories", adminToken(), Map.of("nameEn", "Flash Cat " + UUID.randomUUID(), "nameGu", "શ્રેણી", "sortOrder", 98));
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      categoryId = (String) res.getBody().get("id");
    }
    return categoryId;
  }

  /** A brand new product (unique name) so a test never touches the seeded data. */
  protected Map<String, Object> newProduct(long pricePaise, int stock) {
    Map<String, Object> body = new HashMap<>(Map.of("categoryId", category(), "nameEn", "Flash Item " + UUID.randomUUID(), "nameGu", "નામ",
        "mrpPaise", pricePaise, "pricePaise", pricePaise, "unit", "pack", "packSize", "1 pc", "qtyOnHand", stock));
    var res = post("/api/v1/admin/products", adminToken(), body);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  protected Map<String, Object> item(Object productId, Integer maxQty) {
    Map<String, Object> i = new HashMap<>();
    i.put("productId", productId);
    if (maxQty != null) i.put("maxQty", maxQty);
    return i;
  }

  /** A sale body that starts now and runs for an hour; override any field through {@code extra}. */
  protected Map<String, Object> saleBody(List<Map<String, Object>> items, Map<String, Object> extra) {
    Map<String, Object> b = new HashMap<>();
    b.put("name", "Sale " + UUID.randomUUID().toString().substring(0, 8));
    b.put("endsAt", Instant.now().plusSeconds(3600).toString());
    b.put("discountKind", "PERCENT");
    b.put("discountValue", 20);
    b.put("items", items);
    b.putAll(extra);
    return b;
  }

  protected Map<String, Object> createSale(Map<String, Object> body) {
    var res = post("/api/v1/admin/flash-sales", adminToken(), body);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    caches.invalidate(AppCaches.Name.FLASH);
    return res.getBody();
  }

  protected Map<String, Object> createRunning(Object productId, Integer maxQty, Map<String, Object> extra) {
    return createSale(saleBody(List.of(item(productId, maxQty)), extra));
  }

  protected ResponseEntity<Map> delete(String url) { return call(HttpMethod.DELETE, url, adminToken(), null, null); }

  protected void cancelSale(Object saleId) { post("/api/v1/admin/flash-sales/" + saleId + "/cancel", adminToken(), null); }

  protected Map<String, Object> sale(Object id) { return get("/api/v1/admin/flash-sales/" + id, adminToken()).getBody(); }

  protected Map<String, Object> saleItem(Object saleId, Object productId) {
    return ((List<Map<String, Object>>) sale(saleId).get("items")).stream().filter(i -> productId.equals(i.get("productId"))).findFirst().orElseThrow();
  }

  protected Map<String, Object> quote(String token, Object productId, int qty) {
    var res = post("/api/v1/cart/quote", token, Map.of("items", List.of(Map.of("productId", productId, "qty", qty)), "coinMode", "NONE",
        "fulfilment", "PICKUP"));
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
    return res.getBody();
  }

  /** Places a COD pick-up order for the amount the quote shows. Returns the whole response. */
  protected ResponseEntity<Map> placeOrder(String token, Object productId, int qty, long expectedTotal) {
    return call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", List.of(Map.of("productId", productId, "qty", qty)), "coinMode", "NONE",
        "fulfilment", "PICKUP", "paymentMethod", "COD", "expectedTotalPaise", expectedTotal), UUID.randomUUID().toString());
  }

  protected String order(String token, Object productId, int qty) {
    long total = ((Number) quote(token, productId, qty).get("totalPaise")).longValue();
    var res = placeOrder(token, productId, qty, total);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  protected Map<String, Object> posQuote(Object productId, int qty) {
    var res = post("/api/v1/pos/quote", cashier(), Map.of("items", List.of(Map.of("productId", productId, "qty", qty))));
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
    return res.getBody();
  }

  protected ResponseEntity<Map> posSale(Object productId, int qty, long pay, String customerMobile) {
    Map<String, Object> cart = new HashMap<>();
    cart.put("items", List.of(Map.of("productId", productId, "qty", qty)));
    if (customerMobile != null) cart.put("customerMobile", customerMobile);
    return call(HttpMethod.POST, "/api/v1/pos/sales", cashier(), Map.of("cart", cart, "payments", List.of(Map.of("mode", "CASH", "amountPaise", pay))),
        UUID.randomUUID().toString());
  }

  protected long soldQty(Object itemId) {
    return jdbc.queryForObject("select sold_qty from flash_sale_item where id = ?::uuid", Long.class, itemId.toString());
  }

  protected long liveConsumed(Object saleId) {
    return jdbc.queryForObject("select coalesce(sum(qty), 0) from flash_consumption where flash_sale_id = ?::uuid and released_at is null",
        Long.class, saleId.toString());
  }

  protected List<Map<String, Object>> shopItems(String path) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> s : (List<Map<String, Object>>) get(path, null).getBody().get("sales")) {
      for (Map<String, Object> i : (List<Map<String, Object>>) s.get("items")) {
        Map<String, Object> m = new HashMap<>(i);
        m.put("saleId", s.get("id"));
        out.add(m);
      }
    }
    return out;
  }
}
