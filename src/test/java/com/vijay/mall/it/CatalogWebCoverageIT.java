package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.catalog.domain.UnitOfMeasure;
import com.vijay.mall.catalog.infra.ProductRepository;
import com.vijay.mall.catalog.infra.ReviewRepository;
import com.vijay.mall.catalog.infra.UnitRepository;
import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Units CRUD, product variants and groups, admin category/banner edits and the catalog repositories, over HTTP and directly. */
@SuppressWarnings({"rawtypes", "unchecked"})
class CatalogWebCoverageIT extends IntegrationTestBase {
  @Autowired ProductRepository productRepo;
  @Autowired ReviewRepository reviewRepo;
  @Autowired UnitRepository unitRepo;

  private String code() { return "u" + UUID.randomUUID().toString().substring(0, 8); }

  private String newCategory(String admin) {
    var res = post("/api/v1/admin/categories", admin, Map.of("nameEn", "Cat " + UUID.randomUUID(), "nameGu", "શ્રેણી"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  private Map<String, Object> productBody(String category, String name, Map<String, Object> extra) {
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

  // ---------- units ----------

  @Test
  void unitsCreateListUpdateAndDelete() {
    String admin = adminToken();
    String c = code();
    var created = post("/api/v1/admin/units", admin, Map.of("code", c, "nameEn", "  Bundle ", "nameGu", " ગુચ્છો ", "soldBy", "weight"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).containsEntry("code", c).containsEntry("nameEn", "Bundle").containsEntry("nameGu", "ગુચ્છો")
        .containsEntry("soldBy", "WEIGHT").containsEntry("sortOrder", 0).containsEntry("active", true);

    assertThat(post("/api/v1/admin/units", admin, Map.of("code", c.toUpperCase(), "nameEn", "dup")).getBody())
        .as("codes are case-insensitive").containsEntry("code", "UNIT_EXISTS");
    assertThat(getList("/api/v1/units", null)).extracting(u -> u.get("code")).contains(c);
    assertThat(getList("/api/v1/admin/units", admin)).extracting(u -> u.get("code")).contains(c);
    assertThat(get("/api/v1/admin/units", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    var edited = call(HttpMethod.PUT, "/api/v1/admin/units/" + c.toUpperCase(), admin,
        Map.of("nameEn", "Bunch", "sortOrder", 7, "active", false), null);
    assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(edited.getBody()).containsEntry("code", c).containsEntry("nameEn", "Bunch").containsEntry("nameGu", "")
        .containsEntry("soldBy", "UNIT").containsEntry("sortOrder", 7).containsEntry("active", false);
    assertThat(getList("/api/v1/units", null)).extracting(u -> u.get("code")).doesNotContain(c);
    assertThat(getList("/api/v1/admin/units", admin)).extracting(u -> u.get("code")).contains(c);

    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/units/" + c, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(getList("/api/v1/admin/units", admin)).extracting(u -> u.get("code")).doesNotContain(c);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/units/" + c, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/units/" + c, admin, Map.of("nameEn", "x"), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void unitValidationRejectsBadInput() {
    String admin = adminToken();
    assertThat(post("/api/v1/admin/units", admin, Map.of("nameEn", "No code")).getBody()).containsEntry("code", "VALIDATION_FAILED");
    assertThat(post("/api/v1/admin/units", admin, Map.of("code", "x".repeat(21), "nameEn", "Long")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/units", admin, Map.of("code", code(), "nameEn", "   ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/units", admin, Map.of("code", code(), "nameEn", "Bad", "soldBy", "METRE")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/units", admin, Map.of("code", "   ", "nameEn", "Blank")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    String c = code();
    post("/api/v1/admin/units", admin, Map.of("code", c, "nameEn", "Tmp"));
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/units/" + c, admin, Map.of("code", "y".repeat(21), "nameEn", "Tmp"), null)
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    String other = code();
    post("/api/v1/admin/units", admin, Map.of("code", other, "nameEn", "Other"));
    var clash = call(HttpMethod.PUT, "/api/v1/admin/units/" + c, admin, Map.of("code", other, "nameEn", "Tmp"), null);
    assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(clash.getBody()).containsEntry("code", "UNIT_EXISTS");
    call(HttpMethod.DELETE, "/api/v1/admin/units/" + c, admin, null, null);
    call(HttpMethod.DELETE, "/api/v1/admin/units/" + other, admin, null, null);
  }

  @Test
  void renamingAUnitMovesItsProductsAndAUnitInUseCannotBeDeleted() {
    String admin = adminToken();
    String from = code();
    String to = code();
    post("/api/v1/admin/units", admin, Map.of("code", from, "nameEn", "Renamable"));
    var p = createProduct(admin, productBody(newCategory(admin), "UnitUser " + UUID.randomUUID(), Map.of("unit", from.toUpperCase())));
    assertThat(p).as("canonical spelling stored").containsEntry("unit", from);

    var inUse = call(HttpMethod.DELETE, "/api/v1/admin/units/" + from, admin, null, null);
    assertThat(inUse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(inUse.getBody()).containsEntry("code", "UNIT_IN_USE");

    var renamed = call(HttpMethod.PUT, "/api/v1/admin/units/" + from, admin, Map.of("code", to, "nameEn", "Renamed"), null);
    assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(renamed.getBody()).containsEntry("code", to).containsEntry("nameEn", "Renamed");
    assertThat(get("/api/v1/admin/products/" + p.get("id"), admin).getBody()).containsEntry("unit", to);
    assertThat(unitRepo.productsUsing(to)).isEqualTo(1);
    assertThat(unitRepo.productsUsing(from)).isZero();
    assertThat(unitRepo.findIgnoreCase(from)).isEmpty();
  }

  @Test
  void unitRepositoryHandlesMissingGujaratiNameAndUnknownUpdate() {
    String c = code();
    var saved = unitRepo.insert(new UnitOfMeasure(c, "Direct", null, "UNIT", 3, true));
    assertThat(saved.nameGu()).isEmpty();
    assertThat(unitRepo.update(c, new UnitOfMeasure(c, "Direct2", null, "WEIGHT", 4, false))).get()
        .satisfies(u -> assertThat(u.nameEn()).isEqualTo("Direct2"));
    assertThat(unitRepo.update("nosuch-" + c, saved)).isEmpty();
    assertThat(unitRepo.allActive()).extracting(UnitOfMeasure::code).doesNotContain(c);
    assertThat(unitRepo.all()).extracting(UnitOfMeasure::code).contains(c);
    unitRepo.delete(c);
    assertThat(unitRepo.findIgnoreCase(c)).isEmpty();
  }

  // ---------- variants, groups, admin edits ----------

  @Test
  void variantsAreCreatedRenamedAndEditedAsAGroup() {
    String admin = adminToken();
    String cat = newCategory(admin);
    String name = "Grp " + UUID.randomUUID();
    var base = createProduct(admin, productBody(cat, name, Map.of("variantName", "Small", "variantOrder", 2)));
    String id = (String) base.get("id");
    var variants = getList("/api/v1/admin/products/" + id + "/variants", admin);
    assertThat(variants).hasSize(1);

    // variant name only, order only (update path too)
    var named = call(HttpMethod.PUT, "/api/v1/admin/products/" + id, admin, productBody(cat, name, Map.of("variantName", " Tiny ")), null);
    assertThat(named.getStatusCode()).isEqualTo(HttpStatus.OK);
    var ordered = call(HttpMethod.PUT, "/api/v1/admin/products/" + id, admin, productBody(cat, name, Map.of("variantOrder", 5)), null);
    assertThat(ordered.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(createProduct(admin, productBody(cat, "Solo " + UUID.randomUUID(), Map.of("variantOrder", 1)))).containsKey("id");

    // add variants
    var big = post("/api/v1/admin/products/" + id + "/variants", admin,
        Map.of("variantName", "Big", "packSize", "1 kg", "mrpPaise", 5000, "pricePaise", 4500, "qtyOnHand", 3, "variantOrder", 0, "active", false));
    assertThat(big.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(big.getBody()).containsEntry("unit", "pack").containsEntry("active", false).containsEntry("lowThreshold", 5);
    var next = post("/api/v1/admin/products/" + id + "/variants", admin,
        Map.of("variantName", "Mid", "packSize", "500 g", "unit", "pack", "mrpPaise", 3000, "pricePaise", 2500, "lowThreshold", 2));
    assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(next.getBody()).containsEntry("lowThreshold", 2).containsEntry("qtyOnHand", 0);
    assertThat(post("/api/v1/admin/products/" + id + "/variants", admin,
        Map.of("variantName", "big", "packSize", "1 kg", "mrpPaise", 1, "pricePaise", 1)).getStatusCode())
        .as("duplicate variant").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/products/" + id + "/variants", admin, Map.of("mrpPaise", 1, "pricePaise", 1)).getStatusCode())
        .as("needs a name or pack size").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(getList("/api/v1/admin/products/" + id + "/variants", admin)).hasSize(3);
    assertThat(get("/api/v1/admin/products/" + UUID.randomUUID() + "/variants", admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    // group edit: active left out keeps variants as they are, defaults for flags
    String cat2 = newCategory(admin);
    String renamed = "Grp renamed " + UUID.randomUUID();
    var edited = call(HttpMethod.PUT, "/api/v1/admin/product-groups/" + big.getBody().get("id"), admin,
        Map.of("categoryId", cat2, "nameEn", renamed, "nameGu", "નવું"), null);
    assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map> after = (List<Map>) edited.getBody().get("_array");
    assertThat(after).hasSize(3).allSatisfy(v -> {
      assertThat(v).containsEntry("nameEn", renamed).containsEntry("categoryId", cat2).containsEntry("hotSale", false);
      assertThat(v).containsEntry("earnsCoins", true).containsEntry("coinRedeemable", true);
    });
    assertThat(after).extracting(v -> v.get("active")).as("the inactive variant stays inactive").contains(false, true);

    // group edit with explicit flags and active=false switches every variant off
    var off = call(HttpMethod.PUT, "/api/v1/admin/product-groups/" + id, admin, Map.of("categoryId", cat2, "nameEn", renamed,
        "nameGu", "નવું", "description", "d", "hotSale", true, "hotStart", Instant.now().minusSeconds(60).toString(),
        "hotEnd", Instant.now().plusSeconds(600).toString(), "earnsCoins", false, "coinRedeemable", false, "active", false), null);
    assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List<Map>) off.getBody().get("_array")).allSatisfy(v -> assertThat(v).containsEntry("active", false)
        .containsEntry("hotSale", true).containsEntry("earnsCoins", false).containsEntry("description", "d"));
    var badWindow = call(HttpMethod.PUT, "/api/v1/admin/product-groups/" + id, admin, Map.of("categoryId", cat2, "nameEn", renamed,
        "nameGu", "x", "hotStart", "2030-01-02T00:00:00Z", "hotEnd", "2030-01-01T00:00:00Z"), null);
    assertThat(badWindow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/product-groups/" + UUID.randomUUID(), admin,
        Map.of("categoryId", cat2, "nameEn", "n", "nameGu", "g"), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void productFlagsDefaultAndCanBeSwitched() {
    String admin = adminToken();
    String cat = newCategory(admin);
    var plain = createProduct(admin, Map.of("categoryId", cat, "nameEn", "Plain " + UUID.randomUUID(), "nameGu", "સાદું",
        "mrpPaise", 100, "pricePaise", 100));
    assertThat(plain).containsEntry("active", true).containsEntry("hotSale", false).containsEntry("earnsCoins", true)
        .containsEntry("coinRedeemable", true).containsEntry("qtyOnHand", 0).containsEntry("lowThreshold", 5);
    var flags = createProduct(admin, productBody(cat, "Flags " + UUID.randomUUID(),
        Map.of("earnsCoins", false, "coinRedeemable", false, "active", false, "hotSale", true)));
    assertThat(flags).containsEntry("active", false).containsEntry("hotSale", true).containsEntry("earnsCoins", false)
        .containsEntry("coinRedeemable", false);
  }

  @Test
  void explicitTrueFlagsAreKept() {
    String admin = adminToken();
    String cat = newCategory(admin);
    var p = createProduct(admin, productBody(cat, "Yes " + UUID.randomUUID(),
        Map.of("active", true, "earnsCoins", true, "coinRedeemable", true, "hotSale", false)));
    assertThat(p).containsEntry("active", true).containsEntry("earnsCoins", true).containsEntry("coinRedeemable", true);
    var half = createProduct(admin, productBody(cat, "Half " + UUID.randomUUID(), Map.of("earnsCoins", false)));
    assertThat(half).containsEntry("earnsCoins", false).containsEntry("coinRedeemable", true);
    var other = createProduct(admin, productBody(cat, "Other " + UUID.randomUUID(), Map.of("coinRedeemable", false)));
    assertThat(other).containsEntry("earnsCoins", true).containsEntry("coinRedeemable", false);

    var catOn = post("/api/v1/admin/categories", admin, Map.of("nameEn", "On " + UUID.randomUUID(), "nameGu", "ક", "active", true));
    assertThat(getList("/api/v1/categories", null)).extracting(c -> c.get("id")).contains(catOn.getBody().get("id"));
    var catUpd = call(HttpMethod.PUT, "/api/v1/admin/categories/" + catOn.getBody().get("id"), admin,
        Map.of("nameEn", "On2 " + UUID.randomUUID(), "nameGu", "ક", "active", true), null);
    assertThat(catUpd.getStatusCode()).isEqualTo(HttpStatus.OK);
    var banner = post("/api/v1/admin/hot-banners", admin, Map.of("title", "T " + UUID.randomUUID(), "active", true));
    assertThat(banner.getBody()).containsEntry("active", true);
    var bannerUpd = call(HttpMethod.PUT, "/api/v1/admin/hot-banners/" + banner.getBody().get("id"), admin,
        Map.of("title", "T2", "active", true), null);
    assertThat(bannerUpd.getBody()).containsEntry("active", true);
    call(HttpMethod.DELETE, "/api/v1/admin/hot-banners/" + banner.getBody().get("id"), admin, null, null);

    String c = code();
    var unit = post("/api/v1/admin/units", admin, Map.of("code", c, "nameEn", "On", "active", true, "sortOrder", 1));
    assertThat(unit.getBody()).containsEntry("active", true).containsEntry("sortOrder", 1);
    call(HttpMethod.DELETE, "/api/v1/admin/units/" + c, admin, null, null);
  }

  @Test
  void adminListFiltersAndStockMovements() {
    String admin = adminToken();
    String cat = newCategory(admin);
    String name = "Findable" + UUID.randomUUID();
    var p = createProduct(admin, productBody(cat, name, Map.of()));
    var byCat = (List<Map>) get("/api/v1/admin/products?categoryId=" + cat + "&q=" + name, admin).getBody().get("items");
    assertThat(byCat).extracting(x -> x.get("id")).containsExactly(p.get("id"));
    assertThat(post("/api/v1/admin/products/" + p.get("id") + "/stock", admin, Map.of("delta", 2)).getStatusCode()).isEqualTo(HttpStatus.OK);
    var moves = get("/api/v1/admin/stock-movements?productId=" + p.get("id"), admin).getBody();
    assertThat((List<Map>) moves.get("items")).isNotEmpty().allSatisfy(m -> assertThat(m).containsEntry("productId", p.get("id")));
    assertThat(get("/api/v1/admin/stock-movements?size=2", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void categoriesAndBannersTakeDefaultsAndCanBeSwitchedOff() {
    String admin = adminToken();
    var created = post("/api/v1/admin/categories", admin, Map.of("nameEn", "C " + UUID.randomUUID(), "nameGu", "ક", "sortOrder", 4, "active", false));
    assertThat(created.getBody()).containsEntry("sortOrder", 4);
    String id = (String) created.getBody().get("id");
    var upd = call(HttpMethod.PUT, "/api/v1/admin/categories/" + id, admin, Map.of("nameEn", "C2 " + UUID.randomUUID(), "nameGu", "ક"), null);
    assertThat(upd.getBody()).containsEntry("sortOrder", 0);
    assertThat(getList("/api/v1/categories", null)).extracting(c -> c.get("id")).as("update without active switches it on").contains(id);
    var upd2 = call(HttpMethod.PUT, "/api/v1/admin/categories/" + id, admin,
        Map.of("nameEn", "C3 " + UUID.randomUUID(), "nameGu", "ક", "sortOrder", 9, "active", false), null);
    assertThat(upd2.getBody()).containsEntry("sortOrder", 9);
    assertThat(getList("/api/v1/categories", null)).extracting(c -> c.get("id")).doesNotContain(id);

    var banner = post("/api/v1/admin/hot-banners", admin, Map.of("title", "B " + UUID.randomUUID(), "active", false));
    assertThat(banner.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(banner.getBody()).containsEntry("active", false);
    String bid = (String) banner.getBody().get("id");
    var on = call(HttpMethod.PUT, "/api/v1/admin/hot-banners/" + bid, admin, Map.of("title", "B2 " + UUID.randomUUID()), null);
    assertThat(on.getBody()).containsEntry("active", true);
    var off = call(HttpMethod.PUT, "/api/v1/admin/hot-banners/" + bid, admin, Map.of("title", "B3", "active", false), null);
    assertThat(off.getBody()).containsEntry("active", false).containsEntry("title", "B3");
    var defaulted = post("/api/v1/admin/hot-banners", admin, Map.of("title", "B4 " + UUID.randomUUID()));
    assertThat(defaulted.getBody()).containsEntry("active", true);
    assertThat(getList("/api/v1/admin/hot-banners", admin)).extracting(b -> b.get("id")).contains(bid, defaulted.getBody().get("id"));
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/hot-banners/" + bid, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/hot-banners/" + defaulted.getBody().get("id"), admin, null, null).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
  }

  // ---------- shop-side group and review reads ----------

  @Test
  void groupsAndReviewListsOfProductsWithoutReviews() {
    String admin = adminToken();
    String cat = newCategory(admin);
    var p = createProduct(admin, productBody(cat, "Reviewless " + UUID.randomUUID(), Map.of()));
    String id = (String) p.get("id");
    var one = get("/api/v1/products/" + id + "/reviews", null).getBody();
    assertThat((List<?>) one.get("items")).isEmpty();
    assertThat(one).containsEntry("reviewCount", 0).containsEntry("rating", 0.0);
    var group = get("/api/v1/product-groups/" + id + "/reviews", null).getBody();
    assertThat((List<?>) group.get("items")).isEmpty();
    assertThat(group).containsEntry("reviewCount", 0).containsEntry("rating", 0.0);

    assertThat(((List<?>) get("/api/v1/product-groups?q=zzzz-" + UUID.randomUUID() + "&categoryId=" + cat, null).getBody().get("items")))
        .isEmpty();
    assertThat((List<Map>) get("/api/v1/product-groups?categoryId=" + cat + "&q=Reviewless", null).getBody().get("items")).hasSize(1);
    assertThat((List<Map>) get("/api/v1/product-groups?includeOutOfStock=true&hot=true&size=5", null).getBody().get("items")).isNotNull();
    assertThat(get("/api/v1/product-groups/" + UUID.randomUUID() + "/reviews", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blankSearchTextIsIgnored() {
    String t = token(freshEmail());
    var all = get("/api/v1/products?q=&size=5", t).getBody();
    assertThat((List<?>) all.get("items")).hasSize(5);
  }

  // ---------- repositories, direct ----------

  @Test
  void productRepositoryEdgeCases() {
    assertThat(productRepo.findAll(List.of())).isEmpty();
    assertThat(productRepo.variantInfo(List.of())).isEmpty();
    assertThat(productRepo.visibleMembers(List.of())).isEmpty();
    assertThat(productRepo.posOnlyAmong(List.of())).isEmpty();
    UUID missing = UUID.randomUUID();
    productRepo.setVariant(missing, "n", 1);
    assertThat(productRepo.isPosOnly(missing)).isFalse();
    assertThat(productRepo.updateShared(missing, UUID.randomUUID(), "a", "b", "", false, null, null, true, true, null, Instant.now()))
        .isZero();

    String admin = adminToken();
    var p = createProduct(admin, productBody(newCategory(admin), "Repo " + UUID.randomUUID(), Map.of()));
    UUID id = UUID.fromString((String) p.get("id"));
    productRepo.setVariant(id, null, 3);
    assertThat(productRepo.variantInfo(List.of(id)).get(id)).isEqualTo(new ProductRepository.VariantInfo("", 3));
    assertThat(productRepo.posOnlyAmong(List.of(id))).isEmpty();
    productRepo.setPosOnly(id, true);
    assertThat(productRepo.posOnlyAmong(List.of(id))).isEqualTo(Set.of(id));
    productRepo.setPosOnly(id, false);
    assertThat(productRepo.find(id)).get().satisfies(d -> assertThat(d.productGroupId()).isEqualTo(id));
    assertThat(productRepo.names(List.of(id))).containsKey(id);
    assertThat(productRepo.activeNames(List.of(id))).containsKey(id);
  }

  @Test
  void reviewRepositoryEdgeCases() {
    assertThat(reviewRepo.pageOf(List.of(), 0, 5).items()).isEmpty();
    assertThat(reviewRepo.summaryOf(List.of())).isEqualTo(new ReviewRepository.Summary(0, 0));
    String admin = adminToken();
    var p = createProduct(admin, productBody(newCategory(admin), "Rev " + UUID.randomUUID(), Map.of()));
    UUID pid = UUID.fromString((String) p.get("id"));
    assertThat(reviewRepo.summaryOf(List.of(pid))).isEqualTo(new ReviewRepository.Summary(0, 0));
    assertThat(reviewRepo.summary(pid)).isEqualTo(new ReviewRepository.Summary(0, 0));

    UUID user = UUID.fromString((String) ((Map) login(freshEmail()).get("user")).get("id"));
    var row = reviewRepo.upsert(pid, user, 4, "ok", null, Instant.now());
    assertThat(row.verified()).as("no order, not verified").isFalse();
    assertThat(reviewRepo.find(pid, user)).get().satisfies(r -> assertThat(r.rating()).isEqualTo(4));
    assertThat(reviewRepo.summaryOf(List.of(pid))).isEqualTo(new ReviewRepository.Summary(4.0, 1));
    assertThat(reviewRepo.pageOf(List.of(pid), 0, 5).items()).hasSize(1);
    var again = reviewRepo.upsert(pid, user, 2, "meh", null, Instant.now());
    assertThat(again.id()).as("second write replaces the first").isEqualTo(row.id());
    assertThat(reviewRepo.page(pid, 0, 5).total()).isEqualTo(1);
  }
}
