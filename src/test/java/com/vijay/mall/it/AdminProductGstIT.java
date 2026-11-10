package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The admin product list and detail show each product's saved GST rate and HSN, never a stale one. */
@SuppressWarnings({"unchecked", "rawtypes"})
class AdminProductGstIT extends IntegrationTestBase {
  private String product(String name) {
    String category = jdbc.queryForObject("select id::text from category limit 1", String.class);
    var res = post("/api/v1/admin/products", adminToken(), Map.of("categoryId", category, "nameEn", name, "nameGu", "વસ્તુ",
        "mrpPaise", 20000, "pricePaise", 10000, "unit", "pack", "packSize", "1 kg", "qtyOnHand", 5));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  private Map<String, Object> row(String q, String id) {
    var items = (List<Map<String, Object>>) get("/api/v1/admin/products?size=50&q=" + q, adminToken()).getBody().get("items");
    return items.stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
  }

  @Test
  void rowsAndDetailCarryTheRateAndHsnAndTheListIsNeverStale() {
    String tag = "AdmGst" + UUID.randomUUID().toString().substring(0, 8);
    String unset = product(tag + " plain");
    String set = product(tag + " rated");

    assertThat(row(tag, unset)).containsEntry("gstPercent", 0).containsEntry("hsn", "").containsEntry("gstConfigured", false)
        .containsEntry("nameEn", tag + " plain").containsEntry("pricePaise", 10000).containsEntry("qtyAvailable", 5);
    assertThat(row(tag, set)).containsEntry("gstConfigured", false);

    var saved = call(HttpMethod.PUT, "/api/v1/admin/products/" + set + "/pos", adminToken(),
        Map.of("soldBy", "UNIT", "gstPercent", new BigDecimal("1.5"), "hsn", "1006"), null);
    assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);

    // the product list itself may be served from the catalog cache; the rate must still be today's
    assertThat(row(tag, set)).containsEntry("gstPercent", 1.5).containsEntry("hsn", "1006").containsEntry("gstConfigured", true);
    assertThat(row(tag, unset)).containsEntry("gstConfigured", false);
    assertThat(get("/api/v1/admin/products/" + set, adminToken()).getBody()).containsEntry("gstPercent", 1.5).containsEntry("hsn", "1006")
        .containsEntry("gstConfigured", true).containsEntry("nameEn", tag + " rated");
    assertThat(get("/api/v1/admin/products/" + unset, adminToken()).getBody()).containsEntry("gstPercent", 0).containsEntry("gstConfigured", false);

    call(HttpMethod.PUT, "/api/v1/admin/products/" + set + "/pos", adminToken(), Map.of("soldBy", "UNIT", "gstPercent", 0, "hsn", "1006"), null);
    assertThat(row(tag, set)).containsEntry("gstPercent", 0).containsEntry("gstConfigured", true);
  }

  @Test
  void aListWithNoMatchIsEmptyAndAnUnknownProductIsNotFound() {
    assertThat((List) get("/api/v1/admin/products?q=zz-no-such-product-" + UUID.randomUUID(), adminToken()).getBody().get("items")).isEmpty();
    assertThat(get("/api/v1/admin/products/" + UUID.randomUUID(), adminToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
