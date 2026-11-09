package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.FlashTestBase;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

/** Round 3 of the frontend findings over real HTTP: images, shop Gujarati texts, whole numbers, sibling validation, flash, segments, stock list. */
@SuppressWarnings({"unchecked", "rawtypes"})
class BackendRound3IT extends FlashTestBase {

  private static byte[] image(int type, String format) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(600, 300, type), format, out);
    return out.toByteArray();
  }

  private ResponseEntity<Map> uploadLogo(byte[] bytes) {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return "logo.png"; } });
    return client().post().uri("/api/v1/admin/shop-profile/logo").headers(h -> h.setBearerAuth(adminToken()))
        .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().toEntity(Map.class);
  }

  private Map<String, Object> fields(ResponseEntity<Map> res) {
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.BAD_REQUEST);
    return (Map<String, Object>) ((Map) res.getBody().get("details")).get("fields");
  }

  // ---------- R2-18, R2-19 images ----------
  @Test
  void imagesAreCachedForAYearAndAnAlphaLogoStaysPng() throws Exception {
    var res = uploadLogo(image(BufferedImage.TYPE_INT_ARGB, "png"));
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
    String url = (String) res.getBody().get("logoUrl");
    assertThat(url).startsWith("/img/shop/").endsWith("_400.png");
    var served = client().get().uri(url).retrieve().toEntity(byte[].class);
    assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
    HttpHeaders h = served.getHeaders();
    assertThat(h.getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(h.getCacheControl()).contains("public", "max-age=31536000", "immutable").doesNotContain("no-store").doesNotContain("no-cache");
    assertThat(h.getFirst(HttpHeaders.EXPIRES)).isNull();
    assertThat(h.getFirst(HttpHeaders.PRAGMA)).isNull();
    assertThat(h.getFirst(HttpHeaders.LAST_MODIFIED)).isNotNull();
    var again = client().get().uri(url).header(HttpHeaders.IF_MODIFIED_SINCE, h.getFirst(HttpHeaders.LAST_MODIFIED)).retrieve().toBodilessEntity();
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    var big = client().get().uri(url.replace("_400", "_1000")).retrieve().toEntity(byte[].class);
    assertThat(big.getStatusCode()).isEqualTo(HttpStatus.OK);
    var opaque = uploadLogo(image(BufferedImage.TYPE_INT_RGB, "png"));
    assertThat((String) opaque.getBody().get("logoUrl")).endsWith("_400.jpg");
    call(HttpMethod.DELETE, "/api/v1/admin/shop-profile/logo", adminToken(), null, null);
  }

  // ---------- R4 shop icons ----------
  @Test
  void aLogoGetsSquareIconsThatAreServedCachedAndCleanedUpOnReplaceAndRemove() throws Exception {
    String admin = adminToken();
    var res = uploadLogo(image(BufferedImage.TYPE_INT_ARGB, "png"));
    String icon512 = (String) res.getBody().get("iconUrl");
    String icon192 = (String) res.getBody().get("icon192Url");
    assertThat(icon512).startsWith("/img/shop/").endsWith("_icon512.png");
    assertThat(icon192).endsWith("_icon192.png");
    var pub = get("/api/v1/shop-settings", null).getBody();
    assertThat(pub).containsEntry("iconUrl", icon512).containsEntry("icon192Url", icon192);
    var served = client().get().uri(icon192).retrieve().toEntity(byte[].class);
    assertThat(served.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(served.getHeaders().getCacheControl()).contains("public", "max-age=31536000", "immutable");
    var img = ImageIO.read(new java.io.ByteArrayInputStream(served.getBody()));
    assertThat(img.getWidth()).isEqualTo(192);
    assertThat(img.getHeight()).isEqualTo(192);
    String oldLogo = (String) res.getBody().get("logoUrl");

    var replaced = uploadLogo(image(BufferedImage.TYPE_INT_RGB, "png"));
    assertThat((String) replaced.getBody().get("iconUrl")).isNotEqualTo(icon512);
    assertThat(client().get().uri(oldLogo).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(client().get().uri(icon512).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    String newIcon = (String) replaced.getBody().get("iconUrl");

    var rebuilt = call(HttpMethod.POST, "/api/v1/admin/shop-profile/logo/icon", admin, null, null);
    assertThat(rebuilt.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(rebuilt.getBody()).containsEntry("iconUrl", newIcon);

    call(HttpMethod.DELETE, "/api/v1/admin/shop-profile/logo", admin, null, null);
    assertThat(client().get().uri(newIcon).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var after = get("/api/v1/shop-settings", null).getBody();
    assertThat(after.get("iconUrl")).isNull();
    assertThat(after.get("icon192Url")).isNull();
    var none = call(HttpMethod.POST, "/api/v1/admin/shop-profile/logo/icon", admin, null, null).getStatusCode();
    assertThat(none).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ---------- R2-20, R2-21 shop texts ----------
  @Test
  void shopGujaratiTextsAndShortNameAreSavedAndPublic() {
    String admin = adminToken();
    var before = get("/api/v1/admin/shop-settings", admin).getBody();
    Map<String, Object> body = new HashMap<>(before);
    body.remove("weighedMode");
    body.put("shopName", before.get("shopName"));
    body.put("shortName", "Vijay Mall");
    body.put("shopNameGu", "વિજય સુપર મોલ");
    body.put("addressGu", "અમદાવાદ");
    body.put("promiseGu", "તાજું અને સસ્તું");
    body.put("detailsGu", "અમારા વિશે");
    var saved = call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, body, null);
    assertThat(saved.getStatusCode()).as(String.valueOf(saved.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(saved.getBody()).containsEntry("shortName", "Vijay Mall").containsEntry("shopNameGu", "વિજય સુપર મોલ");

    var profile = get("/api/v1/admin/shop-profile", admin).getBody();
    Map<String, Object> pb = new HashMap<>(profile);
    pb.put("holidayNoteGu", "દિવાળી બંધ");
    var ps = call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, pb, null);
    assertThat(ps.getStatusCode()).as(String.valueOf(ps.getBody())).isEqualTo(HttpStatus.OK);
    var pub = get("/api/v1/shop-settings", null).getBody();
    assertThat(pub).containsEntry("shortName", "Vijay Mall").containsEntry("shopNameGu", "વિજય સુપર મોલ").containsEntry("addressGu", "અમદાવાદ")
        .containsEntry("promiseGu", "તાજું અને સસ્તું").containsEntry("detailsGu", "અમારા વિશે").containsEntry("holidayNoteGu", "દિવાળી બંધ");

    // a PUT that leaves the Gujarati keys out keeps them; "" clears them
    Map<String, Object> keep = new HashMap<>(body);
    keep.keySet().removeAll(List.of("shortName", "shopNameGu", "addressGu", "promiseGu", "detailsGu"));
    call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, keep, null);
    call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, new HashMap<>(profile) {{ remove("holidayNoteGu"); }}, null);
    var kept = get("/api/v1/shop-settings", null).getBody();
    assertThat(kept).containsEntry("shopNameGu", "વિજય સુપર મોલ").containsEntry("holidayNoteGu", "દિવાળી બંધ");
    body.put("shortName", "");
    body.put("shopNameGu", "");
    body.put("addressGu", "");
    body.put("promiseGu", "");
    body.put("detailsGu", "");
    call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, body, null);
    call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, new HashMap<>(profile) {{ put("holidayNoteGu", ""); }}, null);
    var cleared = get("/api/v1/shop-settings", null).getBody();
    assertThat(cleared).containsEntry("shortName", "").containsEntry("shopNameGu", "").containsEntry("holidayNoteGu", "");
    call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, before, null);
  }

  @Test
  void shopTextLimitsAndPhoneFormatAreEnforced() {
    String admin = adminToken();
    Map<String, Object> body = new HashMap<>(get("/api/v1/admin/shop-settings", admin).getBody());
    body.put("shortName", "x".repeat(21));
    body.put("shopName", "n".repeat(81));
    body.put("address", "a".repeat(301));
    body.put("receiptFooter", "f".repeat(201));
    body.put("phone", "abc");
    var f = fields(call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, body, null));
    assertThat(f).containsKeys("shortName", "shopName", "address", "receiptFooter", "phone");
    for (String bad : List.of("12345", "1234567890123456", "98-76 +54x")) {
      body.put("phone", bad);
      body.put("shortName", "ok");
      body.put("shopName", "Shop");
      body.put("address", "A");
      body.put("receiptFooter", "F");
      assertThat(fields(call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin, body, null))).containsKey("phone");
    }
    var profile = new HashMap<>(get("/api/v1/admin/shop-profile", admin).getBody());
    profile.put("holidayNoteGu", "h".repeat(201));
    assertThat(fields(call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, profile, null))).containsKey("holidayNoteGu");
  }

  // ---------- S-D1 whole numbers ----------
  @Test
  void aFractionForAWholeNumberFieldIsRefusedNotCutDown() {
    String admin = adminToken();
    var p = newProduct(1000, 5);
    String stockUrl = "/api/v1/admin/products/" + p.get("id") + "/stock";
    assertThat(fields(call(HttpMethod.POST, stockUrl, admin, "{\"delta\":1.5,\"reason\":\"RESTOCK\"}", null))).containsEntry("delta", "must be a whole number");
    assertThat(fields(call(HttpMethod.POST, stockUrl, admin, "{\"delta\":5.0,\"reason\":\"RESTOCK\"}", null))).containsEntry("delta", "must be a whole number");
    assertThat(fields(call(HttpMethod.PUT, "/api/v1/admin/coin-settings", admin, "{\"paisePerCoin\":1.5,\"maxRedeemPercent\":50,\"expiryMonths\":12}", null)))
        .containsEntry("paisePerCoin", "must be a whole number");
    String customer = (String) ((Map) login(freshEmail()).get("user")).get("id");
    assertThat(fields(call(HttpMethod.POST, "/api/v1/admin/customers/" + customer + "/coins", admin, "{\"delta\":5.5,\"reason\":\"gift\"}", null)))
        .containsEntry("delta", "must be a whole number");
    assertThat(call(HttpMethod.POST, stockUrl, admin, "{\"delta\":3,\"reason\":\"RESTOCK\"}", null).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void stockDeltaAndCoinSettingsHaveBounds() {
    String admin = adminToken();
    var p = newProduct(1000, 5);
    String stockUrl = "/api/v1/admin/products/" + p.get("id") + "/stock";
    assertThat(fields(call(HttpMethod.POST, stockUrl, admin, Map.of("delta", 1_000_001), null))).containsEntry("delta", "must be 1000000 or less");
    assertThat(fields(call(HttpMethod.POST, stockUrl, admin, Map.of("delta", -1_000_001), null))).containsKey("delta");
    assertThat(fields(call(HttpMethod.PUT, "/api/v1/admin/coin-settings", admin, Map.of("paisePerCoin", 100, "maxRedeemPercent", 50, "expiryMonths", 121), null)))
        .containsKey("expiryMonths");
  }

  // ---------- S-D3 / S-D2 siblings and limits ----------
  @Test
  void siblingEndpointsValidateTheSameWay() {
    String admin = adminToken();
    var p = newProduct(1000, 5);
    Map<String, Object> variant = new HashMap<>(Map.of("variantName", "Big", "packSize", "2 kg", "mrpPaise", 2000, "pricePaise", 1800, "qtyOnHand", -3));
    assertThat(fields(call(HttpMethod.POST, "/api/v1/admin/products/" + p.get("id") + "/variants", admin, variant, null)))
        .containsEntry("qtyOnHand", "must be 0 or more");

    Map<String, Object> coupon = new HashMap<>(Map.of("code", "R3" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "kind", "FLAT", "value", 500,
        "minOrderPaise", -1));
    assertThat(fields(call(HttpMethod.POST, "/api/v1/admin/coupons", admin, coupon, null))).containsEntry("minOrderPaise", "must be 0 or more");

    Instant now = Instant.now();
    Map<String, Object> rule = Map.of("name", "R3 rule", "spendPaise", 10000, "coins", 1, "mode", "PRO_RATA",
        "startAt", now.plusSeconds(3600).toString(), "endAt", now.toString());
    assertThat(fields(call(HttpMethod.POST, "/api/v1/admin/coin-rules", admin, rule, null))).containsEntry("endAt", "must be after startAt");
    Map<String, Object> banner = Map.of("title", "R3 banner", "startAt", now.plusSeconds(3600).toString(), "endAt", now.toString());
    assertThat(fields(call(HttpMethod.POST, "/api/v1/admin/hot-banners", admin, banner, null))).containsEntry("endAt", "must be after startAt");
    var okRule = new HashMap<>(rule);
    okRule.put("endAt", now.plusSeconds(7200).toString());
    var created = call(HttpMethod.POST, "/api/v1/admin/coin-rules", admin, okRule, null);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(fields(call(HttpMethod.PUT, "/api/v1/admin/coin-rules/" + created.getBody().get("id"), admin, rule, null))).containsKey("endAt");
    call(HttpMethod.DELETE, "/api/v1/admin/coin-rules/" + created.getBody().get("id"), admin, null, null);
    var okBanner = call(HttpMethod.POST, "/api/v1/admin/hot-banners", admin, Map.of("title", "R3 ok", "startAt", now.toString(), "endAt", now.plusSeconds(60).toString()), null);
    assertThat(okBanner.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(fields(call(HttpMethod.PUT, "/api/v1/admin/hot-banners/" + okBanner.getBody().get("id"), admin, banner, null))).containsKey("endAt");
    call(HttpMethod.DELETE, "/api/v1/admin/hot-banners/" + okBanner.getBody().get("id"), admin, null, null);
  }

  @Test
  void textAndMoneyLimitsAreEnforcedOnTheCatalogAndCoupons() {
    String admin = adminToken();
    Map<String, Object> body = new HashMap<>(Map.of("categoryId", category(), "nameEn", "n".repeat(121), "nameGu", "ગ".repeat(121),
        "description", "d".repeat(2001), "mrpPaise", 100_000_001L, "pricePaise", 100_000_001L, "unit", "pack"));
    var f = fields(post("/api/v1/admin/products", admin, body));
    assertThat(f).containsKeys("nameEn", "nameGu", "description", "mrpPaise", "pricePaise");
    var patch = call(HttpMethod.PATCH, "/api/v1/admin/products/" + newProduct(1000, 1).get("id"), admin, Map.of("nameEn", "n".repeat(121)), null);
    assertThat(fields(patch)).containsKey("nameEn");
    String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    for (String code : List.of("HAS SPACE" + suffix, "bad!" + suffix, "X".repeat(31))) {
      Map<String, Object> c = Map.of("code", code, "kind", "FLAT", "value", 100);
      assertThat(fields(call(HttpMethod.POST, "/api/v1/admin/coupons", admin, c, null))).containsKey("code");
    }
    var good = call(HttpMethod.POST, "/api/v1/admin/coupons", admin, Map.of("code", " r3-ok_" + suffix + " ", "kind", "FLAT", "value", 100), null);
    assertThat(good.getStatusCode()).as(String.valueOf(good.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(good.getBody()).containsEntry("code", "R3-OK_" + suffix);
    call(HttpMethod.DELETE, "/api/v1/admin/coupons/" + good.getBody().get("id"), admin, null, null);
    assertThat(fields(call(HttpMethod.POST, "/api/v1/admin/coupons", admin, Map.of("code", "BIG" + suffix, "kind", "FLAT", "value", 100_000_001L), null)))
        .containsKey("value");
    assertThat(fields(call(HttpMethod.POST, "/api/v1/admin/categories", admin, Map.of("nameEn", "c".repeat(121), "nameGu", "x"), null))).containsKey("nameEn");
  }

  // ---------- R2-16 flash ----------
  @Test
  void flashValidationReportsEveryFailingFieldAndASaleCanBeDeletedBeforeItStarts() {
    Map<String, Object> bad = new HashMap<>();
    bad.put("name", "");
    bad.put("discountKind", "PERCENT");
    bad.put("discountValue", 95);
    bad.put("endsAt", Instant.now().plusSeconds(3600).toString());
    var f = fields(post("/api/v1/admin/flash-sales", adminToken(), bad));
    assertThat(f).containsKeys("name", "discountValue", "items");
    assertThat(fields(post("/api/v1/admin/flash-sales", cashier(), Map.of("name", "", "endsAt", "x-not-a-date")))).isNotEmpty();

    var p = newProduct(10000, 5);
    var future = createSale(saleBody(List.of(item(p.get("id"), 3)),
        Map.of("startsAt", Instant.now().plusSeconds(1800).toString(), "endsAt", Instant.now().plusSeconds(5400).toString())));
    String id = (String) future.get("id");
    var patchBad = call(HttpMethod.PATCH, "/api/v1/admin/flash-sales/" + id, adminToken(), Map.of("name", "", "discountValue", 99, "perCustomerLimit", 5000), null);
    assertThat(fields(patchBad)).containsKeys("name", "discountValue", "perCustomerLimit");

    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/flash-sales/" + id, cashier(), null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(get("/api/v1/admin/flash-sales/" + id, adminToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(delete("/api/v1/admin/flash-sales/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'FLASH_DELETE' and entity_id = ?", Long.class, id)).isEqualTo(1L);

    var running = createRunning(newProduct(10000, 5).get("id"), 3, Map.of());
    var res = delete("/api/v1/admin/flash-sales/" + running.get("id"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody()).containsEntry("code", "FLASH_NOT_ALLOWED");
    cancelSale(running.get("id"));
    assertThat(post("/api/v1/admin/flash-sales", null, saleBody(List.of(), Map.of())).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/flash-sales/" + id, token(freshEmail()), null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void aScheduledSaleWithRecordedUseCannotBeDeleted() {
    var p = newProduct(10000, 5);
    var future = createSale(saleBody(List.of(item(p.get("id"), 3)),
        Map.of("startsAt", Instant.now().plusSeconds(1800).toString(), "endsAt", Instant.now().plusSeconds(5400).toString())));
    String id = (String) future.get("id");
    jdbc.update("update flash_sale_item set sold_qty = 1 where flash_sale_id = ?::uuid", id);
    var res = delete("/api/v1/admin/flash-sales/" + id);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody()).containsEntry("code", "FLASH_NOT_ALLOWED");
    jdbc.update("update flash_sale_item set sold_qty = 0 where flash_sale_id = ?::uuid", id);
    jdbc.update("insert into flash_consumption (id, flash_item_id, flash_sale_id, product_id, channel, pos_sale_id, qty, unit_price_paise, "
        + "list_price_paise, discount_paise, created_at) select gen_random_uuid(), i.id, i.flash_sale_id, i.product_id, 'POS', gen_random_uuid(), 1, 0, 0, 0, now() from flash_sale_item i where i.flash_sale_id = ?::uuid", id);
    assertThat(delete("/api/v1/admin/flash-sales/" + id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    jdbc.update("delete from flash_consumption where flash_sale_id = ?::uuid", id);
    assertThat(delete("/api/v1/admin/flash-sales/" + id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  // ---------- segments, broadcast ----------
  @Test
  void segmentErrorsListEveryFieldAndTheEitherOrMessageIsAccurate() {
    String admin = adminToken();
    var f = fields(post("/api/v1/admin/notifications/segments", admin, Map.of("name", "", "rules", List.of())));
    assertThat(f).containsKeys("name", "rules");
    var neither = post("/api/v1/admin/notifications/segments/preview", admin, Map.of());
    assertThat(fields(neither)).containsEntry("segment", "give a segmentId or a segment (match and rules)");
    var both = post("/api/v1/admin/notifications/segments/preview", admin,
        Map.of("segmentId", UUID.randomUUID().toString(), "match", "ALL", "rules", List.of(Map.of("type", "HAS_REVIEWED"))));
    assertThat(fields(both)).containsEntry("segment", "give either a segmentId or a segment, not both");
    var broadcast = post("/api/v1/admin/notifications/broadcast", admin, Map.of("audience", "SEGMENT", "title", "Hi", "body", "There"));
    assertThat(fields(broadcast)).containsEntry("segment", "give a segmentId or a segment (match and rules)");
  }

  @Test
  void conditionCatalogSaysRupeesAndTellsTheAppHowToConvert() {
    var catalog = (List<Map<String, Object>>) get("/api/v1/admin/notifications/segments/condition-types", adminToken()).getBody().get("_array");
    var spent = catalog.stream().filter(e -> "TOTAL_SPENT".equals(e.get("type"))).findFirst().orElseThrow();
    var paise = ((List<Map<String, Object>>) spent.get("params")).stream().filter(x -> "paise".equals(x.get("name"))).findFirst().orElseThrow();
    assertThat(paise).containsEntry("label", "Amount (Rs)").containsEntry("inputUnit", "RUPEES").containsEntry("scale", 100).containsEntry("kind", "AMOUNT_PAISE");
    var op = ((List<Map<String, Object>>) spent.get("params")).stream().filter(x -> "op".equals(x.get("name"))).findFirst().orElseThrow();
    assertThat(op.get("inputUnit")).isNull();
    assertThat(catalog.stream().flatMap(e -> ((List<Map<String, Object>>) e.get("params")).stream()).map(x -> (String) x.get("label")))
        .noneMatch(l -> l.contains("(paise)"));
  }

  // ---------- R2-17 stock list, P-5 ----------
  @Test
  void retiredProductsDoNotAppearInTheLowStockList() {
    String admin = adminToken();
    var retired = newProduct(1000, 0);
    var live = newProduct(1000, 0);
    assertThat(call(HttpMethod.PATCH, "/api/v1/admin/products/" + retired.get("id"), admin, Map.of("active", false), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    var low = (List<Map<String, Object>>) get("/api/v1/admin/stock/low?limit=200", admin).getBody().get("_array");
    assertThat(low).extracting(m -> m.get("productId")).contains(live.get("id")).doesNotContain(retired.get("id"));
  }

  @Test
  void aBadResolveActionNamesTheAllowedWordsAndTheValueGiven() {
    String admin = adminToken();
    var res = post("/api/v1/admin/stock/defective/" + UUID.randomUUID() + "/resolve", admin, Map.of("action", "burn"));
    assertThat(fields(res)).containsEntry("action", "must be WRITE_OFF or RETURN_TO_SUPPLIER (got \"burn\")");
    assertThat(fields(post("/api/v1/admin/stock/defective/" + UUID.randomUUID() + "/resolve", admin, new HashMap<String, Object>()))).containsKey("action");
  }

  @Test
  void swaggerPublishesTheBroadcastRequestNameAndTheConditionTypeValues() {
    String docs = client().get().uri("/v3/api-docs").retrieve().body(String.class);
    assertThat(docs).contains("\"BroadcastRequest\"").contains("\"HAS_DEVICE\"").contains("\"ORDER_COUNT\"");
    assertThat(new ArrayList<>(List.of(docs))).isNotEmpty();
  }
}
