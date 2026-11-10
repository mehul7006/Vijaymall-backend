package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Decimal GST rates at the counter: saving them, the JSON number format, bills, receipts, returns and offline bills. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PosDecimalGstIT extends IntegrationTestBase {
  private static final String MESSAGE = "must be one of 0, 0.25, 1.5, 3, 5, 12, 18, 28 or 40";

  private String cashier() { return token("cashier@vijaysupermall.local"); }

  /** A fresh product of 101.50 with 100 in stock and no counter attributes saved. */
  private String product() {
    String category = jdbc.queryForObject("select id::text from category limit 1", String.class);
    var res = post("/api/v1/admin/products", adminToken(), Map.of("categoryId", category, "nameEn", "Dec GST " + UUID.randomUUID(), "nameGu", "વસ્તુ",
        "mrpPaise", 20000, "pricePaise", 10150, "unit", "pack", "packSize", "1 kg", "qtyOnHand", 100));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  private org.springframework.http.ResponseEntity<Map> savePos(String id, Map<String, Object> body) {
    return call(HttpMethod.PUT, "/api/v1/admin/products/" + id + "/pos", adminToken(), body, null);
  }

  private Map<String, Object> pos(String soldBy, Object gst, String hsn, boolean withGst, boolean withHsn) {
    Map<String, Object> m = new HashMap<>();
    m.put("soldBy", soldBy);
    if (withGst) m.put("gstPercent", gst);
    if (withHsn) m.put("hsn", hsn);
    return m;
  }

  @Test
  void ratesAreSavedReadBackAsPlainNumbersAndOthersAreRefused() {
    String id = product();
    for (Object[] c : new Object[][] {{new BigDecimal("0.25"), 0.25}, {new BigDecimal("1.50"), 1.5}, {40, 40}, {18.0, 18}, {5, 5}, {0, 0}}) {
      var res = savePos(id, pos("UNIT", c[0], "1006", true, true));
      assertThat(res.getStatusCode()).as(String.valueOf(c[0])).isEqualTo(HttpStatus.OK);
      assertThat(res.getBody().get("gstPercent")).isEqualTo(c[1]);
      assertThat(get("/api/v1/admin/products/" + id + "/pos", adminToken()).getBody().get("gstPercent")).isEqualTo(c[1]);
    }
    for (Object bad : new Object[] {18.5, 1, 0.5, 41, -1}) {
      var res = savePos(id, pos("UNIT", bad, "1006", true, true));
      assertThat(res.getStatusCode()).as(String.valueOf(bad)).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(((Map) ((Map) res.getBody().get("details")).get("fields")).get("gstPercent")).isEqualTo(MESSAGE);
    }
    assertThat(jdbc.queryForObject("select gst_percent from product_pos where product_id = ?::uuid", BigDecimal.class, id)).isEqualByComparingTo("0");
  }

  @Test
  void aRateOrHsnLeftOutOrNullKeepsWhatIsStoredAndNothingStoredMeansZeroAndNoHsn() {
    String nothing = product();
    var first = savePos(nothing, pos("UNIT", null, null, false, false));
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody()).containsEntry("gstPercent", 0).containsEntry("hsn", "");

    String id = product();
    assertThat(savePos(id, pos("UNIT", new BigDecimal("1.5"), "1006", true, true)).getStatusCode()).isEqualTo(HttpStatus.OK);
    var omitted = savePos(id, pos("UNIT", null, null, false, false)).getBody();
    assertThat(omitted).containsEntry("gstPercent", 1.5).containsEntry("hsn", "1006");
    var nulls = savePos(id, pos("UNIT", null, null, true, true)).getBody();
    assertThat(nulls).containsEntry("gstPercent", 1.5).containsEntry("hsn", "1006");
    var blank = savePos(id, pos("UNIT", null, "  ", false, true)).getBody();
    assertThat(blank).containsEntry("hsn", "1006");
    var explicit = savePos(id, pos("UNIT", 0, "2106", true, true)).getBody();
    assertThat(explicit).containsEntry("gstPercent", 0).containsEntry("hsn", "2106");
    var onlyRate = savePos(id, pos("UNIT", 40, null, true, false)).getBody();
    assertThat(onlyRate).containsEntry("gstPercent", 40).containsEntry("hsn", "2106");
  }

  /*
   * One piece at 101.50 and 1.5% GST: taxable 10150 * 100 / 101.5 = 10000, tax 150 = 75 + 75. Two pieces: 20000, 150 + 150.
   * Returning one piece refunds 10150 with taxable 10000, CGST 75, SGST 75.
   */
  @Test
  void aBillReceiptAndReturnAtOneAndAHalfPercent() {
    String id = product();
    savePos(id, pos("UNIT", new BigDecimal("1.5"), "1006", true, true));
    String cashier = cashier();

    var quote = post("/api/v1/pos/quote", cashier, Map.of("items", List.of(Map.of("productId", id, "qty", 1)))).getBody();
    assertThat(((Map) ((List) quote.get("lines")).get(0)).get("gstPercent")).isEqualTo(1.5);
    assertThat(quote).containsEntry("taxablePaise", 10000).containsEntry("cgstPaise", 75).containsEntry("sgstPaise", 75);

    var scan = get("/api/v1/pos/products?q=Dec%20GST&size=50", cashier);
    assertThat(scan.getStatusCode()).isEqualTo(HttpStatus.OK);

    var created = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", id, "qty", 2))),
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", 20300))), UUID.randomUUID().toString());
    assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
    Map sale = created.getBody();
    assertThat(sale).containsEntry("taxablePaise", 20000).containsEntry("cgstPaise", 150).containsEntry("sgstPaise", 150);
    Map item = (Map) ((List) sale.get("items")).get(0);
    assertThat(item.get("gstPercent")).isEqualTo(1.5);
    assertThat(jdbc.queryForObject("select gst_percent from pos_sale_item where id = ?::uuid", BigDecimal.class, item.get("id"))).isEqualByComparingTo("1.5");
    assertThat((String) get("/api/v1/pos/sales/" + sale.get("id") + "/receipt?width=32", cashier).getBody().get("text")).contains("(1.5%)");

    var ret = call(HttpMethod.POST, "/api/v1/pos/sales/" + sale.get("id") + "/returns", cashier,
        Map.of("items", List.of(Map.of("saleItemId", item.get("id"), "qty", 1)), "reason", "damaged"), UUID.randomUUID().toString());
    assertThat(ret.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(ret.getBody()).containsEntry("refundPaise", 10150).containsEntry("taxablePaise", 10000).containsEntry("cgstPaise", 75)
        .containsEntry("sgstPaise", 75);
    assertThat((String) get("/api/v1/pos/sales/" + sale.get("id") + "/returns/" + ret.getBody().get("id") + "/receipt?width=32", cashier)
        .getBody().get("text")).contains("(1.5%)");
  }

  /*
   * An offline till bills the 101.50 piece at 40%: 10150 * 100 / 140 = 7250 exactly, tax 2900 = 1450 + 1450. The catalog says 1.5, so
   * the bill carries a GST_CHANGED exception. The same rate written as 1.50 is the catalog rate and raises nothing.
   */
  @Test
  void anOfflineBillKeepsTheTillsFractionalRateAndFlagsOnlyRealDifferences() {
    String id = product();
    savePos(id, pos("UNIT", new BigDecimal("1.5"), "1006", true, true));
    String cashier = cashier();

    var changed = upload(cashier, rung(id, 40));
    assertThat(changed.get("status")).isEqualTo("CREATED");
    assertThat((List<Map>) changed.get("exceptions")).extracting(e -> e.get("kind")).containsExactly("GST_CHANGED");
    Map sale = get("/api/v1/pos/sales/" + changed.get("saleId"), cashier).getBody();
    assertThat(sale).containsEntry("taxablePaise", 7250).containsEntry("cgstPaise", 1450).containsEntry("sgstPaise", 1450);
    assertThat(((Map) ((List) sale.get("items")).get(0)).get("gstPercent")).isEqualTo(40);

    var same = upload(cashier, rung(id, new BigDecimal("1.50")));
    assertThat(same.get("status")).isEqualTo("CREATED");
    assertThat((List) same.get("exceptions")).isEmpty();
    Map sameSale = get("/api/v1/pos/sales/" + same.get("saleId"), cashier).getBody();
    assertThat(sameSale).containsEntry("taxablePaise", 10000).containsEntry("cgstPaise", 75);
    assertThat(((Map) ((List) sameSale.get("items")).get(0)).get("gstPercent")).isEqualTo(1.5);

    var bad = upload(cashier, rung(id, 18.5));
    assertThat(bad.get("status")).isEqualTo("REJECTED");
    assertThat(bad.get("errorCode")).isEqualTo("VALIDATION_FAILED");
    assertThat(String.valueOf(bad)).contains(MESSAGE);
  }

  private Map rung(String id, Object gst) {
    Map<String, Object> line = new HashMap<>();
    line.put("productId", id);
    line.put("qty", 1);
    line.put("unitPricePaise", 10150);
    line.put("gstPercent", gst);
    Map<String, Object> bill = new HashMap<>();
    bill.put("idempotencyKey", UUID.randomUUID().toString());
    bill.put("soldAt", Instant.now().minusSeconds(600).toString());
    bill.put("rung", Map.of("lines", List.of(line)));
    bill.put("payments", List.of(Map.of("mode", "CASH", "amountPaise", 10150)));
    return bill;
  }

  private Map upload(String token, Map bill) {
    var res = post("/api/v1/pos/sync/sales", token, Map.of("sales", List.of(bill)));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    return ((List<Map>) res.getBody().get("results")).get(0);
  }
}
