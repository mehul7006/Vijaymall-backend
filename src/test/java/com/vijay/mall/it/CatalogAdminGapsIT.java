package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

/** End to end: image scopes, product delete, partial update, category admin, hot-sale paging and the customer detail lists. */
@SuppressWarnings({"rawtypes", "unchecked"})
class CatalogAdminGapsIT extends IntegrationTestBase {

  private String newCategory(String admin) {
    var res = post("/api/v1/admin/categories", admin, Map.of("nameEn", "Gap " + UUID.randomUUID(), "nameGu", "શ્રેણી", "sortOrder", 98));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(res.getBody()).containsEntry("active", true).containsEntry("sortOrder", 98).containsKeys("id", "nameEn", "nameGu", "imageUrl");
    return (String) res.getBody().get("id");
  }

  private Map<String, Object> newProduct(String admin, String category, Map<String, Object> extra) {
    Map<String, Object> b = new HashMap<>(Map.of("categoryId", category, "nameEn", "Gap " + UUID.randomUUID(), "nameGu", "નામ",
        "mrpPaise", 2000, "pricePaise", 1800, "unit", "pack", "packSize", "100 g", "qtyOnHand", 10));
    b.putAll(extra);
    var res = post("/api/v1/admin/products", admin, b);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private Map<String, Object> newVariant(String admin, String baseId, String pack) {
    var res = post("/api/v1/admin/products/" + baseId + "/variants", admin,
        Map.of("variantName", pack, "packSize", pack, "mrpPaise", 3000, "pricePaise", 2500, "qtyOnHand", 5));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private byte[] png() throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", out);
    return out.toByteArray();
  }

  private ResponseEntity<Map> upload(String path, byte[] bytes) {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return "a.png"; } });
    return client().post().uri(path).headers(h -> h.setBearerAuth(adminToken()))
        .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().toEntity(Map.class);
  }

  private String imageOf(String admin, Object id) { return (String) get("/api/v1/admin/products/" + id, admin).getBody().get("imageUrl"); }

  // ---------- B10-01 ----------
  @Test
  void imageVariantsModesCoverOneMissingAndAll() throws Exception {
    String admin = adminToken();
    var p1 = newProduct(admin, newCategory(admin), Map.of());
    String id1 = (String) p1.get("id");
    String id2 = (String) newVariant(admin, id1, "200 g").get("id");
    String id3 = (String) newVariant(admin, id1, "300 g").get("id");

    // default: one pack only
    assertThat(upload("/api/v1/admin/products/" + id1 + "/image", png()).getStatusCode()).isEqualTo(HttpStatus.OK);
    String first = imageOf(admin, id1);
    assertThat(first).isNotNull();
    assertThat(imageOf(admin, id2)).isNull();
    assertThat(imageOf(admin, id3)).isNull();

    // missing: the addressed pack and the packs without a picture
    var missing = upload("/api/v1/admin/products/" + id2 + "/image?variants=missing", png());
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(missing.getBody()).containsEntry("id", id2);
    String second = imageOf(admin, id2);
    assertThat(second).isNotNull().isNotEqualTo(first);
    assertThat(imageOf(admin, id1)).as("a pack that had a picture keeps it").isEqualTo(first);
    assertThat(imageOf(admin, id3)).isEqualTo(second);

    // all: overwrite everything
    assertThat(upload("/api/v1/admin/products/" + id3 + "/image?variants=all", png()).getStatusCode()).isEqualTo(HttpStatus.OK);
    String third = imageOf(admin, id3);
    assertThat(third).isNotEqualTo(second);
    assertThat(imageOf(admin, id1)).isEqualTo(third);
    assertThat(imageOf(admin, id2)).isEqualTo(third);

    assertThat(upload("/api/v1/admin/products/" + id1 + "/image?variants=bogus", png()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aCustomerVariantWithoutAPictureShowsTheGroupPicture() throws Exception {
    String admin = adminToken();
    String id1 = (String) newProduct(admin, newCategory(admin), Map.of()).get("id");
    String id2 = (String) newVariant(admin, id1, "250 g").get("id");
    assertThat(upload("/api/v1/admin/products/" + id2 + "/image", png()).getStatusCode()).isEqualTo(HttpStatus.OK);
    var group = get("/api/v1/product-groups/" + id1, token(freshEmail())).getBody();
    var variants = (List<Map>) group.get("variants");
    assertThat(variants).hasSize(2).allSatisfy(v -> assertThat(v.get("imageUrl")).isEqualTo(imageOf(admin, id2)));
  }

  // ---------- B10-02 ----------
  @Test
  void aNeverSoldProductIsRemovedCompletely() {
    String admin = adminToken();
    String id = (String) newProduct(admin, newCategory(admin), Map.of()).get("id");
    assertThat(jdbc.queryForObject("select count(*) from inventory where product_id = ?::uuid", Integer.class, id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from stock_movement where product_id = ?::uuid", Integer.class, id)).isNotNull();

    var res = call(HttpMethod.DELETE, "/api/v1/admin/products/" + id, admin, null, null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).containsEntry("deleted", true).containsEntry("deactivated", false).containsKey("reason");
    assertThat(get("/api/v1/admin/products/" + id, admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(jdbc.queryForObject("select count(*) from inventory where product_id = ?::uuid", Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from stock_movement where product_id = ?::uuid", Integer.class, id)).isZero();
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/products/" + id, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/products/" + id, token(freshEmail()), null, null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    var audit = (List<Map>) get("/api/v1/admin/audit-log?action=PRODUCT_DELETE&entityId=" + id, admin).getBody().get("items");
    assertThat(audit).hasSize(1);
  }

  @Test
  void aSoldProductIsOnlySwitchedOffAndRepeatsStayOk() {
    String admin = adminToken();
    String id = (String) newProduct(admin, newCategory(admin), Map.of()).get("id");
    String customer = token(freshEmail());
    Map<String, Object> cart = Map.of("items", List.of(Map.of("productId", id, "qty", 1)), "coinMode", "NONE", "fulfilment", "PICKUP");
    Map<String, Object> order = new HashMap<>(cart);
    order.put("paymentMethod", "COD");
    order.put("expectedTotalPaise", ((Number) post("/api/v1/cart/quote", customer, cart).getBody().get("totalPaise")).longValue());
    assertThat(call(HttpMethod.POST, "/api/v1/orders", customer, order, UUID.randomUUID().toString()).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    var res = call(HttpMethod.DELETE, "/api/v1/admin/products/" + id, admin, null, null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).containsEntry("deleted", false).containsEntry("deactivated", true);
    assertThat(get("/api/v1/admin/products/" + id, admin).getBody()).containsEntry("active", false);
    assertThat(get("/api/v1/products/" + id, customer).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var again = call(HttpMethod.DELETE, "/api/v1/admin/products/" + id, admin, null, null);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(again.getBody()).containsEntry("deactivated", true);
    var audit = (List<Map>) get("/api/v1/admin/audit-log?action=PRODUCT_DEACTIVATE&entityId=" + id, admin).getBody().get("items");
    assertThat(audit).hasSize(1);
  }

  // ---------- B10-13 ----------
  @Test
  void patchChangesOnlyWhatIsSentAndNeverTouchesStock() {
    String admin = adminToken();
    Instant start = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    Instant end = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    var p = newProduct(admin, newCategory(admin), Map.of("hotSale", true, "hotStart", start.toString(), "hotEnd", end.toString(),
        "lowThreshold", 3));
    String id = (String) p.get("id");
    String url = "/api/v1/admin/products/" + id;

    var after = call(HttpMethod.PATCH, url, admin, Map.of("pricePaise", 1500), null);
    assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(after.getBody()).containsEntry("pricePaise", 1500).containsEntry("mrpPaise", 2000).containsEntry("qtyOnHand", 10)
        .containsEntry("lowThreshold", 3).containsEntry("nameEn", p.get("nameEn")).containsEntry("hotSale", true);
    assertThat(Instant.parse((String) after.getBody().get("hotStart"))).isEqualTo(start);

    var cleared = call(HttpMethod.PATCH, url, admin, Map.of("description", "new text"), null);
    assertThat(cleared.getBody()).containsEntry("description", "new text").containsEntry("pricePaise", 1500);

    Map<String, Object> clear = new HashMap<>();
    clear.put("hotStart", null);
    clear.put("hotEnd", null);
    clear.put("variantName", "Big");
    clear.put("variantOrder", 2);
    clear.put("active", false);
    var res = call(HttpMethod.PATCH, url, admin, clear, null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).containsEntry("active", false).containsEntry("qtyOnHand", 10);
    assertThat(res.getBody().get("hotStart")).isNull();
    assertThat(res.getBody().get("hotEnd")).isNull();
    assertThat(jdbc.queryForObject("select variant_name from product where id = ?::uuid", String.class, id)).isEqualTo("Big");

    var stock = call(HttpMethod.PATCH, url, admin, Map.of("qtyOnHand", 99), null);
    assertThat(stock.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(stock.getBody().toString()).contains("stock endpoint");
    assertThat(call(HttpMethod.PATCH, url, admin, Map.of("pricePaise", 5000), null).getStatusCode()).as("MRP below price").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PATCH, url, admin, Map.of("pricePaise", -1), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PATCH, url, admin, Map.of("hotStart", end.toString(), "hotEnd", start.toString()), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PATCH, url, admin, Map.of("nameEn", " "), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PATCH, "/api/v1/admin/products/" + UUID.randomUUID(), admin, Map.of("pricePaise", 1), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get(url, admin).getBody()).containsEntry("qtyOnHand", 10).containsEntry("pricePaise", 1500);
  }

  // ---------- B10-06 ----------
  @Test
  void hiddenCategoriesCanBeListedReEnabledGivenAPictureAndDeleted() throws Exception {
    String admin = adminToken();
    String id = newCategory(admin);
    var put = call(HttpMethod.PUT, "/api/v1/admin/categories/" + id, admin, Map.of("nameEn", "Hidden " + id, "nameGu", "છુપી", "active", false), null);
    assertThat(put.getBody()).containsEntry("id", id).containsEntry("active", false).containsKeys("nameEn", "nameGu", "imageUrl", "sortOrder");

    var all = (List<Map>) get("/api/v1/admin/categories", admin).getBody().get("_array");
    assertThat(all).anySatisfy(c -> assertThat(c).containsEntry("id", id).containsEntry("active", false).containsKey("imageUrl"));
    assertThat(getList("/api/v1/categories", token(freshEmail()))).noneMatch(c -> id.equals(c.get("id")));
    assertThat(get("/api/v1/admin/categories", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    var withImage = upload("/api/v1/admin/categories/" + id + "/image", png());
    assertThat(withImage.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((String) withImage.getBody().get("imageUrl")).startsWith("/img/categories/");
    assertThat(withImage.getBody()).containsEntry("active", false);
    assertThat(upload("/api/v1/admin/categories/" + id + "/image", new byte[0]).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(upload("/api/v1/admin/categories/" + UUID.randomUUID() + "/image", png()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    var back = call(HttpMethod.PUT, "/api/v1/admin/categories/" + id, admin, Map.of("nameEn", "Back " + id, "nameGu", "પાછી", "active", true), null);
    assertThat(back.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getList("/api/v1/categories", token(freshEmail()))).anyMatch(c -> id.equals(c.get("id")));

    String pid = (String) newProduct(admin, id, Map.of()).get("id");
    var inUse = call(HttpMethod.DELETE, "/api/v1/admin/categories/" + id, admin, null, null);
    assertThat(inUse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(inUse.getBody()).containsEntry("code", "CATEGORY_IN_USE");
    call(HttpMethod.DELETE, "/api/v1/admin/products/" + pid, admin, null, null);

    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/categories/" + id, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/categories/" + id, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void aCategoryUsedByACouponCannotBeDeleted() {
    String admin = adminToken();
    String id = newCategory(admin);
    var coupon = post("/api/v1/admin/coupons", admin, Map.of("code", "GAP" + id.substring(0, 6).toUpperCase(), "kind", "FLAT",
        "value", 500, "categoryIds", List.of(id)));
    assertThat(coupon.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/categories/" + id, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  // ---------- B10-16 ----------
  @Test
  void hotSaleIsPagedAndOrderedBySoonestEnd() {
    String admin = adminToken();
    String cat = newCategory(admin);
    Instant now = Instant.now();
    String late = (String) newProduct(admin, cat, Map.of("nameEn", "A late", "hotSale", true, "hotEnd", now.plus(30, ChronoUnit.DAYS).toString())).get("id");
    String soon = (String) newProduct(admin, cat, Map.of("nameEn", "Z soon", "hotSale", true, "hotEnd", now.plus(1, ChronoUnit.HOURS).toString())).get("id");
    String open = (String) newProduct(admin, cat, Map.of("nameEn", "B open", "hotSale", true)).get("id");
    String t = token(freshEmail());

    List<String> order = new ArrayList<>();
    var first = get("/api/v1/hot-sale?size=2", t).getBody();
    assertThat(first).containsKeys("banners", "products", "page", "size", "total").containsEntry("size", 2).containsEntry("page", 0);
    long total = ((Number) first.get("total")).longValue();
    assertThat(((List) first.get("products"))).hasSize(2);
    for (int page = 0; page * 2L < total; page++) {
      for (Map m : (List<Map>) get("/api/v1/hot-sale?size=2&page=" + page, t).getBody().get("products")) order.add((String) m.get("id"));
    }
    assertThat(order).hasSize((int) total).doesNotHaveDuplicates();
    assertThat(order).contains(late, soon, open);
    assertThat(order.indexOf(soon)).isLessThan(order.indexOf(late));
    assertThat(order.indexOf(late)).isLessThan(order.indexOf(open));

    assertThat(first).containsEntry("totalExact", true).containsKey("hasMore");
    assertThat(get("/api/v1/hot-sale?size=9999", t).getBody()).containsEntry("size", 50).containsKey("hasMore");
    assertThat(get("/api/v1/hot-sale?size=0&page=-1", t).getBody()).containsEntry("size", 1).containsEntry("page", 0);
    assertThat(get("/api/v1/hot-sale", t).getBody()).containsEntry("size", 20);
  }

  // ---------- B10-17 / B10-10 ----------
  @Test
  void adminSeesACustomersCoinLedgerAndOrders() {
    String admin = adminToken();
    String email = freshEmail();
    String t = token(email);
    String id = (String) get("/api/v1/me", t).getBody().get("id");
    post("/api/v1/admin/customers/" + id + "/coins", admin, Map.of("delta", 40, "reason", "welcome"));
    post("/api/v1/admin/customers/" + id + "/coins", admin, Map.of("delta", -10, "reason", "oops"));

    var ledger = get("/api/v1/admin/customers/" + id + "/wallet-ledger?size=1", admin).getBody();
    assertThat(ledger).containsEntry("size", 1).containsEntry("total", 2);
    var rows = (List<Map>) ledger.get("items");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("kind", "ADJUST").containsEntry("coins", -10).containsKeys("orderId", "createdAt", "expiresAt");
    var older = (List<Map>) get("/api/v1/admin/customers/" + id + "/wallet-ledger?size=1&page=1", admin).getBody().get("items");
    assertThat(older.get(0)).containsEntry("coins", 40);
    assertThat(get("/api/v1/admin/customers/" + id + "/wallet-ledger?size=9999", admin).getBody()).containsEntry("size", 50);

    var none = get("/api/v1/admin/customers/" + id + "/orders", admin).getBody();
    assertThat(none).containsEntry("total", 0);

    String pid = (String) productByName(t, "Potato Chips").get("id");
    restock("Potato Chips", 20);
    for (int i = 0; i < 2; i++) {
      Map<String, Object> cart = Map.of("items", List.of(Map.of("productId", pid, "qty", 1)), "coinMode", "NONE", "fulfilment", "PICKUP");
      Map<String, Object> order = new HashMap<>(cart);
      order.put("paymentMethod", "COD");
      order.put("expectedTotalPaise", ((Number) post("/api/v1/cart/quote", t, cart).getBody().get("totalPaise")).longValue());
      assertThat(call(HttpMethod.POST, "/api/v1/orders", t, order, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
    var orders = get("/api/v1/admin/customers/" + id + "/orders?size=1", admin).getBody();
    assertThat(orders).containsEntry("total", 2).containsEntry("size", 1);
    assertThat((List<Map>) orders.get("items")).singleElement().satisfies(o -> assertThat(o)
        .containsEntry("customerEmail", email).containsKeys("orderNo", "status", "totalPaise", "placedAt"));

    assertThat(get("/api/v1/admin/customers/" + UUID.randomUUID() + "/wallet-ledger", admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/customers/" + UUID.randomUUID() + "/orders", admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/customers/" + id + "/orders", t).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void coinErrorsComeBackUnderDeltaAndCoins() {
    String admin = adminToken();
    String id = (String) get("/api/v1/me", token(freshEmail())).getBody().get("id");
    var res = post("/api/v1/admin/customers/" + id + "/coins", admin, Map.of("delta", 0, "reason", "nothing"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var fields = (Map) ((Map) res.getBody().get("details")).get("fields");
    assertThat(fields).containsKeys("delta", "coins");
    assertThat(fields.get("delta")).isEqualTo(fields.get("coins"));
  }
}
