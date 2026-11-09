package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
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
import org.springframework.util.LinkedMultiValueMap;

/** Shop front and admin catalog over HTTP: browsing, search, hot sale, stock, images, categories, banners. */
@SuppressWarnings({"rawtypes", "unchecked"})
class CatalogApiIT extends IntegrationTestBase {

  private String newCategory(String admin) {
    var res = post("/api/v1/admin/categories", admin, Map.of("nameEn", "Cat " + UUID.randomUUID(), "nameGu", "શ્રેણી", "sortOrder", 99));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  private Map<String, Object> product(String category, String name, Map<String, Object> extra) {
    Map<String, Object> b = new HashMap<>(Map.of("categoryId", category, "nameEn", name, "nameGu", "નામ", "mrpPaise", 2000,
        "pricePaise", 1800, "unit", "pack", "packSize", "100 g", "qtyOnHand", 10));
    b.putAll(extra);
    return b;
  }

  private Map<String, Object> createProduct(String admin, Map<String, Object> body) {
    var res = post("/api/v1/admin/products", admin, body);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  @Test
  void shopBrowsingShowsCategoriesAndPagedProducts() {
    String t = token(freshEmail());
    assertThat(getList("/api/v1/categories", t)).extracting(c -> c.get("nameEn")).contains("Grocery", "Dairy");
    var page = get("/api/v1/products?size=3", t).getBody();
    assertThat((List<?>) page.get("items")).hasSize(3);
    assertThat(page).containsEntry("size", 3).containsEntry("page", 0);
    assertThat(((Number) page.get("total")).intValue()).isGreaterThanOrEqualTo(12);
    var tooBig = get("/api/v1/products?size=9999", t).getBody();
    assertThat(tooBig.get("size")).isEqualTo(50);
    var negative = get("/api/v1/products?page=-4", t).getBody();
    assertThat(negative.get("page")).isEqualTo(0);
  }

  @Test
  void searchAndCategoryFiltersNarrowTheList() {
    String t = token(freshEmail());
    var found = (List<Map<String, Object>>) get("/api/v1/products?q=rice", t).getBody().get("items");
    assertThat(found).isNotEmpty().allSatisfy(p -> assertThat(((String) p.get("nameEn")).toLowerCase()).contains("rice"));
    assertThat((List<?>) get("/api/v1/products?q=zzzznothing", t).getBody().get("items")).isEmpty();

    String dairy = (String) getList("/api/v1/categories", t).stream().filter(c -> "Dairy".equals(c.get("nameEn"))).findFirst().orElseThrow().get("id");
    var inDairy = (List<Map<String, Object>>) get("/api/v1/products?categoryId=" + dairy, t).getBody().get("items");
    assertThat(inDairy).isNotEmpty().allSatisfy(p -> assertThat(p.get("categoryId")).isEqualTo(dairy));
  }

  @Test
  void hotSaleShowsOnlyHotProductsAndLiveBanners() {
    String t = token(freshEmail());
    var hot = get("/api/v1/hot-sale", t).getBody();
    assertThat((List<Map<String, Object>>) hot.get("products")).isNotEmpty().allSatisfy(p -> assertThat(p.get("hotSale")).isEqualTo(true));
    var onlyHot = (List<Map<String, Object>>) get("/api/v1/products?hot=true", t).getBody().get("items");
    assertThat(onlyHot).isNotEmpty().allSatisfy(p -> assertThat(p.get("hotSale")).isEqualTo(true));
  }

  @Test
  void hotSaleWindowIsRespected() {
    String admin = adminToken();
    String cat = newCategory(admin);
    String live = "Live " + UUID.randomUUID();
    String later = "Later " + UUID.randomUUID();
    String over = "Over " + UUID.randomUUID();
    createProduct(admin, product(cat, live, Map.of("hotSale", true, "hotStart", Instant.now().minusSeconds(3600).toString(), "hotEnd", Instant.now().plusSeconds(3600).toString())));
    createProduct(admin, product(cat, later, Map.of("hotSale", true, "hotStart", Instant.now().plusSeconds(3600).toString())));
    createProduct(admin, product(cat, over, Map.of("hotSale", true, "hotEnd", Instant.now().minusSeconds(3600).toString())));
    var names = ((List<Map<String, Object>>) get("/api/v1/products?hot=true&categoryId=" + cat, adminToken()).getBody().get("items"))
        .stream().map(p -> p.get("nameEn")).toList();
    assertThat(names).contains(live).doesNotContain(later, over);
  }

  @Test
  void unknownOrInactiveProductIs404ForCustomers() {
    String admin = adminToken();
    String t = token(freshEmail());
    assertThat(get("/api/v1/products/" + UUID.randomUUID(), t).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var p = createProduct(admin, product(newCategory(admin), "Hidden " + UUID.randomUUID(), Map.of("active", false)));
    assertThat(get("/api/v1/products/" + p.get("id"), t).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/products/" + p.get("id"), admin).getStatusCode()).as("admin still sees it").isEqualTo(HttpStatus.OK);
    var list = (List<Map<String, Object>>) get("/api/v1/products?size=50&q=Hidden", t).getBody().get("items");
    assertThat(list).noneMatch(x -> p.get("id").equals(x.get("id")));
  }

  @Test
  void adminCreatesUpdatesAndAdjustsStock() {
    String admin = adminToken();
    String cat = newCategory(admin);
    var p = createProduct(admin, product(cat, "Item " + UUID.randomUUID(), Map.of("qtyOnHand", 10, "lowThreshold", 3)));
    String id = (String) p.get("id");
    assertThat(p).containsEntry("qtyOnHand", 10).containsEntry("inStock", true).containsEntry("lowThreshold", 3);

    var upd = call(HttpMethod.PUT, "/api/v1/admin/products/" + id, admin,
        product(cat, "Renamed " + UUID.randomUUID(), Map.of("pricePaise", 1500, "qtyOnHand", 4, "lowThreshold", 2, "unit", "")), null);
    assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(upd.getBody()).containsEntry("pricePaise", 1500).containsEntry("qtyOnHand", 4).containsEntry("lowThreshold", 2).containsEntry("unit", "pack");

    var restock = post("/api/v1/admin/products/" + id + "/stock", admin, Map.of("delta", 6, "reason", "RESTOCK"));
    assertThat(restock.getBody()).containsEntry("qtyOnHand", 10);
    var adjust = post("/api/v1/admin/products/" + id + "/stock", admin, Map.of("delta", -10, "reason", "ADJUST"));
    assertThat(adjust.getBody()).containsEntry("qtyOnHand", 0).containsEntry("inStock", false);
    var below = post("/api/v1/admin/products/" + id + "/stock", admin, Map.of("delta", -1, "reason", "ADJUST"));
    assertThat(below.getStatusCode()).as("stock cannot go below zero").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/products/" + UUID.randomUUID() + "/stock", admin, Map.of("delta", 1)).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void productValidationRejectsBadPricesAndWindows() {
    String admin = adminToken();
    String cat = newCategory(admin);
    assertThat(post("/api/v1/admin/products", admin, product(cat, "x" + UUID.randomUUID(), Map.of("mrpPaise", 100, "pricePaise", 200))).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/products", admin, product(cat, "x" + UUID.randomUUID(), Map.of("pricePaise", -1))).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/products", admin, product(cat, "x" + UUID.randomUUID(),
        Map.of("hotStart", "2030-01-02T00:00:00Z", "hotEnd", "2030-01-01T00:00:00Z"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/products", admin, Map.of("nameEn", "no category")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/products/" + UUID.randomUUID(), admin, product(cat, "y", Map.of()), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void adminListSupportsSearchAndShowsStock() {
    String admin = adminToken();
    var page = get("/api/v1/admin/products?q=toothpaste&size=5", admin).getBody();
    var items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).isNotEmpty().allSatisfy(p -> assertThat(p).containsKeys("qtyOnHand", "lowThreshold"));
    String cat = (String) items.getFirst().get("categoryId");
    assertThat((List<?>) ((Map) get("/api/v1/admin/products?categoryId=" + cat, admin).getBody()).get("items")).isNotEmpty();
  }

  private static ByteArrayResource png(String filename, byte[] bytes) {
    return new ByteArrayResource(bytes) { @Override public String getFilename() { return filename; } };
  }

  private byte[] imageBytes(String format) throws Exception {
    var img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    ImageIO.write(img, format, out);
    return out.toByteArray();
  }

  private org.springframework.http.ResponseEntity<Map> upload(String id, ByteArrayResource file) {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", file);
    return client().post().uri("/api/v1/admin/products/" + id + "/image").headers(h -> h.setBearerAuth(adminToken()))
        .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().toEntity(Map.class);
  }

  @Test
  void imageUploadStoresAScaledCopyAndServesIt() throws Exception {
    String admin = adminToken();
    var p = createProduct(admin, product(newCategory(admin), "Pic " + UUID.randomUUID(), Map.of()));
    var res = upload((String) p.get("id"), png("a.png", imageBytes("png")));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    String url = (String) res.getBody().get("imageUrl");
    assertThat(url).startsWith("/img/products/").endsWith("_400.jpg");
    var served = client().get().uri(url).retrieve().toEntity(byte[].class);
    assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(served.getBody()).isNotEmpty();
  }

  @Test
  void imageUploadRejectsEmptyAndNonImageFiles() {
    String admin = adminToken();
    var p = createProduct(admin, product(newCategory(admin), "NoPic " + UUID.randomUUID(), Map.of()));
    assertThat(upload((String) p.get("id"), png("a.png", new byte[0])).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(upload((String) p.get("id"), png("a.txt", "hello".getBytes())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void adminManagesCategories() {
    String admin = adminToken();
    String id = newCategory(admin);
    var upd = call(HttpMethod.PUT, "/api/v1/admin/categories/" + id, admin, Map.of("nameEn", "Renamed", "nameGu", "નવું", "active", false), null);
    assertThat(upd.getBody()).containsEntry("nameEn", "Renamed");
    assertThat(getList("/api/v1/categories", token(freshEmail()))).noneMatch(c -> id.equals(c.get("id")));
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/categories/" + UUID.randomUUID(), admin, Map.of("nameEn", "x", "nameGu", "y"), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post("/api/v1/admin/categories", admin, Map.of("nameEn", "", "nameGu", "")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void adminManagesHotBanners() {
    String admin = adminToken();
    String title = "Diwali " + UUID.randomUUID();
    var created = post("/api/v1/admin/hot-banners", admin, Map.of("title", title, "subtitle", "Big sale", "imageUrl", "/img/x.jpg"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) created.getBody().get("id");
    assertThat(((List<Map<String, Object>>) get("/api/v1/hot-sale", token(freshEmail())).getBody().get("banners")))
        .anyMatch(b -> title.equals(b.get("title")));

    var upd = call(HttpMethod.PUT, "/api/v1/admin/hot-banners/" + id, admin,
        Map.of("title", title, "active", false, "endAt", Instant.now().plusSeconds(60).toString()), null);
    assertThat(upd.getBody()).containsEntry("active", false);
    assertThat(((List<Map<String, Object>>) get("/api/v1/hot-sale", token(freshEmail())).getBody().get("banners")))
        .noneMatch(b -> title.equals(b.get("title")));
    assertThat(getList("/api/v1/admin/hot-banners", admin)).anyMatch(b -> id.equals(b.get("id")));

    assertThat(call(HttpMethod.PUT, "/api/v1/admin/hot-banners/" + UUID.randomUUID(), admin, Map.of("title", "x"), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/hot-banners/" + id, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(getList("/api/v1/admin/hot-banners", admin)).noneMatch(b -> id.equals(b.get("id")));
  }

  @Test
  void customersCannotUseAdminCatalog() {
    String t = token(freshEmail());
    assertThat(get("/api/v1/admin/products", t).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(post("/api/v1/admin/categories", t, Map.of("nameEn", "a", "nameGu", "b")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
