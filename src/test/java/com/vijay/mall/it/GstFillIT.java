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

/**
 * Blank GST rate and HSN filled from the GST master: the counter attributes save, the purchase lines and the purchase lookup. A value
 * the caller sent always wins, and a purchase never writes to the product's counter attributes.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class GstFillIT extends IntegrationTestBase {
  private String product(String name) {
    String category = jdbc.queryForObject("select id::text from category limit 1", String.class);
    var res = post("/api/v1/admin/products", adminToken(), Map.of("categoryId", category, "nameEn", name, "nameGu", "વસ્તુ",
        "mrpPaise", 50000, "pricePaise", 45000, "unit", "pack", "packSize", "1 kg", "qtyOnHand", 0));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  private Map putPos(String id, Map<String, Object> body) {
    var res = call(HttpMethod.PUT, "/api/v1/admin/products/" + id + "/pos", adminToken(), body, null);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
    return res.getBody();
  }

  private Map purchase(String product, Map<String, Object> line) {
    Map<String, Object> item = new HashMap<>(Map.of("productId", product, "qty", 2, "unitCostPaise", 10000));
    item.putAll(line);
    var res = call(HttpMethod.POST, "/api/v1/admin/purchases", adminToken(),
        Map.of("supplierName", "Fill Supplier " + UUID.randomUUID().toString().substring(0, 8), "items", List.of(item)), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private long posRows(String id) { return jdbc.queryForObject("select count(*) from product_pos where product_id = ?::uuid", Long.class, id); }

  @Test void savingBlankRateAndHsnFillsThemFromTheMasterAndTheAuditLineNamesTheRow() {
    String id = product("Basmati Rice 5 kg " + UUID.randomUUID().toString().substring(0, 4));
    Map saved = putPos(id, Map.of("soldBy", "UNIT"));
    assertThat(saved).containsEntry("hsn", "1006").containsEntry("gstSource", "MASTER");
    assertThat(((Number) saved.get("gstPercent")).intValue()).isEqualTo(5);
    assertThat(saved.get("gstVerification")).isIn("VERIFIED", "VERIFY_PRODUCT");
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id = ?::text and change like '%(from master %'", Long.class, id)).isEqualTo(1L);

    Map again = putPos(id, Map.of("soldBy", "UNIT", "gstPercent", 12));
    assertThat(((Number) again.get("gstPercent")).intValue()).isEqualTo(12);
    assertThat(again).containsEntry("hsn", "1006").containsEntry("gstSource", "USER").containsEntry("gstVerification", null);
    Map kept = putPos(id, Map.of("soldBy", "UNIT"));
    assertThat(((Number) kept.get("gstPercent")).intValue()).isEqualTo(12);
    assertThat(kept).containsEntry("gstSource", "STORED");
  }

  @Test void aTypedZeroStaysAndNoMatchStaysBlankAndABadHsnIsRefused() {
    String rice = product("Basmati Rice 5 kg " + UUID.randomUUID().toString().substring(0, 4));
    Map zero = putPos(rice, Map.of("soldBy", "UNIT", "gstPercent", 0));
    assertThat(((Number) zero.get("gstPercent")).intValue()).isZero();
    assertThat(zero).containsEntry("hsn", "1006").containsEntry("gstSource", "USER");

    String odd = product("xyzzy plugh " + UUID.randomUUID());
    Map none = putPos(odd, Map.of("soldBy", "UNIT"));
    assertThat(((Number) none.get("gstPercent")).intValue()).isZero();
    assertThat(none).containsEntry("hsn", "").containsEntry("gstSource", "DEFAULT");

    var bad = call(HttpMethod.PUT, "/api/v1/admin/products/" + odd + "/pos", adminToken(), Map.of("soldBy", "UNIT", "hsn", "12345"), null);
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test void lookupAndPurchaseLinesUseTheMasterWithoutCreatingCounterAttributes() {
    String dal = product("Toor Dal " + UUID.randomUUID().toString().substring(0, 4));
    var lookup = get("/api/v1/admin/purchases/lookup?productId=" + dal, adminToken()).getBody();
    assertThat(lookup).containsEntry("hsn", "0713").containsEntry("gstSource", "MASTER");
    assertThat(((Number) lookup.get("gstPercent")).intValue()).isEqualTo(5);

    Map filled = purchase(dal, Map.of());
    Map line = ((List<Map>) filled.get("items")).getFirst();
    assertThat(line).containsEntry("hsn", "0713");
    assertThat(((Number) line.get("gstPercent")).intValue()).isEqualTo(5);
    assertThat(posRows(dal)).isZero();

    String dal2 = product("Toor Dal " + UUID.randomUUID().toString().substring(0, 4));
    Map typed = purchase(dal2, Map.of("gstPercent", 18, "hsn", "999999"));
    Map typedLine = ((List<Map>) typed.get("items")).getFirst();
    assertThat(((Number) typedLine.get("gstPercent")).intValue()).isEqualTo(18);
    assertThat(typedLine).containsEntry("hsn", "999999");

    String dal3 = product("Toor Dal " + UUID.randomUUID().toString().substring(0, 4));
    Map zero = purchase(dal3, Map.of("gstPercent", 0));
    assertThat(((Number) ((List<Map>) zero.get("items")).getFirst().get("gstPercent")).intValue()).isZero();
  }

  @Test void aConfiguredProductsOwnRateBeatsTheMasterInAPurchase() {
    String dal = product("Toor Dal " + UUID.randomUUID().toString().substring(0, 4));
    putPos(dal, Map.of("soldBy", "UNIT", "gstPercent", 12, "hsn", "1111"));
    Map p = purchase(dal, Map.of());
    Map line = ((List<Map>) p.get("items")).getFirst();
    assertThat(((Number) line.get("gstPercent")).intValue()).isEqualTo(12);
    assertThat(line).containsEntry("hsn", "1111");
    assertThat(((List) p.get("warnings"))).isEmpty();
  }

  @Test void aVerifyProductMasterFillAddsAWarningAndAVerifiedOneDoesNot() {
    String id = product("Brass Ganesh " + UUID.randomUUID().toString().substring(0, 4));
    String verification = (String) get("/api/v1/admin/purchases/lookup?productId=" + id, adminToken()).getBody().get("gstVerification");
    Map p = purchase(id, Map.of());
    boolean warned = ((List<String>) p.get("warnings")).stream().anyMatch(w -> w.contains("needs your CA's confirmation"));
    assertThat(warned).isEqualTo("VERIFY_PRODUCT".equals(verification));

    String milk = product("Basmati Rice 5 kg " + UUID.randomUUID().toString().substring(0, 4));
    Map typed = purchase(milk, Map.of("gstPercent", 5, "hsn", "1006"));
    assertThat((List) typed.get("warnings")).isEmpty();
  }
}
