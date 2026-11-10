package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Purchases v2 against real PostgreSQL: GST maths, suppliers, payments, returns and reports. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PurchasingV2IT extends IntegrationTestBase {
  private static final String P = "/api/v1/admin/purchases";
  private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

  private String admin() { return adminToken(); }

  private String key() { return UUID.randomUUID().toString(); }

  private long n(Object o) { return ((Number) o).longValue(); }

  private String supplierName() { return "V2 Supplier " + UUID.randomUUID().toString().substring(0, 8); }

  /** A fresh product with no stock, so every test owns its own numbers. */
  private String product(Object gst, String hsn) {
    String category = jdbc.queryForObject("select id::text from category limit 1", String.class);
    var res = post("/api/v1/admin/products", admin(), Map.of("categoryId", category, "nameEn", "V2 Item " + UUID.randomUUID(), "nameGu", "વસ્તુ",
        "mrpPaise", 20000, "pricePaise", 18000, "unit", "pack", "packSize", "1 kg", "qtyOnHand", 0));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) res.getBody().get("id");
    jdbc.update("insert into product_pos (product_id, gst_percent, hsn, updated_at) values (?::uuid, ?, ?, now())", id, gst, hsn);
    return id;
  }

  private int stock(String id) { return jdbc.queryForObject("select qty_on_hand from inventory where product_id = ?::uuid", Integer.class, id); }

  private Map<String, Object> item(String product, int qty, long unit, Map<String, Object> extra) {
    Map<String, Object> m = new HashMap<>(Map.of("productId", product, "qty", qty, "unitCostPaise", unit));
    m.putAll(extra);
    return m;
  }

  private Map<String, Object> body(String supplier, List<Map<String, Object>> items, Map<String, Object> extra) {
    Map<String, Object> m = new HashMap<>(Map.of("supplierName", supplier, "items", items));
    m.putAll(extra);
    return m;
  }

  private Map create(Map<String, Object> body) {
    var res = call(HttpMethod.POST, P, admin(), body, key());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private Map<String, Object> pay(Object id, long amount, String mode, String k) {
    var res = call(HttpMethod.POST, P + "/" + id + "/payments", admin(), Map.of("amountPaise", amount, "mode", mode, "reference", "UTR1"), k);
    return Map.of("status", res.getStatusCode().value(), "body", res.getBody() == null ? Map.of() : res.getBody());
  }

  private HttpStatus status(Map<String, Object> body) { return HttpStatus.valueOf((Integer) body.get("status")); }

  private String code(Map<String, Object> r) { return (String) ((Map) r.get("body")).get("code"); }

  @Test
  void aGstPurchaseWorksOutLandedCostAppliesPricesAndTakesAPartPayment() {
    String soap = product(18, "3401");
    String supplier = supplierName();
    Map item = item(soap, 10, 10000, Map.of("freeQty", 2, "hsn", "340120", "mrpPaise", 15000, "sellingPricePaise", 14000, "batchNo", "B-1", "expiryDate", "2030-01-31",
        "applyToVariant", true));
    Map p = create(body(supplier, List.of(item), Map.of("supplierGstin", "24ABCDE1234F1Z5", "supplierPhone", "98250 11111", "invoiceNo", "GST-1",
        "paymentStatus", "PARTIAL", "paidPaise", 50000, "paymentMode", "UPI", "paymentRef", "UTR-9", "dueDate", TODAY.plusDays(10).toString())));

    assertThat(n(p.get("taxablePaise"))).isEqualTo(100000);
    assertThat(n(p.get("cgstPaise"))).isEqualTo(9000);
    assertThat(n(p.get("sgstPaise"))).isEqualTo(9000);
    assertThat(n(p.get("igstPaise"))).isZero();
    assertThat(n(p.get("totalCostPaise"))).isEqualTo(118000);
    assertThat(p.get("gstMode")).isEqualTo("REGULAR");
    assertThat(p.get("interState")).isEqualTo(false);
    assertThat(p.get("paymentStatus")).isEqualTo("PARTIAL");
    assertThat(n(p.get("paidPaise"))).isEqualTo(50000);
    assertThat(n(p.get("duePaise"))).isEqualTo(68000);
    assertThat(p.get("overdue")).isEqualTo(false);
    assertThat((List<Map>) p.get("payments")).singleElement().satisfies(x -> assertThat(x).containsEntry("mode", "UPI").containsEntry("reference", "UTR-9"));
    Map line = ((List<Map>) p.get("items")).getFirst();
    assertThat(line).containsEntry("hsn", "340120").containsEntry("batchNo", "B-1").containsEntry("expiryDate", "2030-01-31");
    assertThat(n(line.get("landedTotalPaise"))).isEqualTo(100000);
    assertThat(((Number) line.get("landedUnitCostPaise")).doubleValue()).isEqualTo(8333.3333);
    assertThat(((Number) line.get("landedUnitCostRoundedPaise")).doubleValue()).isEqualTo(8333.0);
    assertThat(stock(soap)).isEqualTo(12);

    var rupee = get(P + "/" + p.get("id") + "?rounding=RUPEE", admin()).getBody();
    assertThat(rupee.get("rounding")).isEqualTo("RUPEE");
    assertThat(((Number) ((List<Map>) rupee.get("items")).getFirst().get("landedUnitCostRoundedPaise")).doubleValue()).isEqualTo(8300.0);
    assertThat(get(P + "/" + p.get("id") + "?rounding=bad", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    var product = get("/api/v1/admin/products/" + soap, admin()).getBody();
    assertThat(n(product.get("mrpPaise"))).isEqualTo(15000);
    assertThat(n(product.get("pricePaise"))).isEqualTo(14000);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'PURCHASE_PRICE_APPLY' and entity_id = ?", Long.class, soap)).isEqualTo(1L);

    var supplierRow = ((List<Map>) get("/api/v1/admin/suppliers?q=" + supplier.substring(12), admin()).getBody().get("items")).getFirst();
    assertThat(supplierRow).containsEntry("name", supplier).containsEntry("gstin", "24ABCDE1234F1Z5").containsEntry("stateCode", "24")
        .containsEntry("phone", "98250 11111").containsEntry("lastInvoiceNo", "GST-1");
    assertThat(n(supplierRow.get("purchaseCount"))).isEqualTo(1);
    assertThat(n(supplierRow.get("totalDuePaise"))).isEqualTo(68000);

    var lookup = get(P + "/lookup?productId=" + soap, admin()).getBody();
    assertThat(n(lookup.get("lastPurchaseCostPaise"))).isEqualTo(10000);
    assertThat(lookup).containsEntry("hsn", "3401").containsEntry("lastSupplierName", supplier);
    assertThat(n(lookup.get("gstPercent"))).isEqualTo(18);
    var fresh = get(P + "/lookup?productId=" + product(5, ""), admin()).getBody();
    assertThat(fresh.get("lastPurchaseCostPaise")).isNull();
    assertThat(get(P + "/lookup?productId=" + UUID.randomUUID(), admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    var history = getList("/api/v1/admin/products/" + soap + "/purchase-prices?limit=5&rounding=EXACT", admin());
    assertThat(history).singleElement().satisfies(h -> {
      assertThat(h).containsEntry("supplierName", supplier).containsEntry("invoiceNo", "GST-1");
      assertThat(n(h.get("unitCostPaise"))).isEqualTo(10000);
      assertThat(((Number) h.get("landedUnitCostRoundedPaise")).doubleValue()).isEqualTo(8333.3333);
    });
    assertThat(get("/api/v1/admin/products/" + UUID.randomUUID() + "/purchase-prices", admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void defaultsComeFromTheProductAndAnOtherStateSupplierChargesIgst() {
    String item = product(12, "1905");
    Map p = create(body(supplierName(), List.of(item(item, 5, 20000, Map.of("cessPercent", 1))),
        Map.of("supplierGstin", "27ABCDE1234F1Z5", "gstMode", "composition", "freightPaise", 1000, "freightGstPercent", 18)));
    assertThat(p).containsEntry("interState", true).containsEntry("supplierStateCode", "27").containsEntry("gstMode", "COMPOSITION");
    assertThat(n(p.get("igstPaise"))).isEqualTo(12000 + 180);
    assertThat(n(p.get("cgstPaise")) + n(p.get("sgstPaise"))).isZero();
    assertThat(n(p.get("cessPaise"))).isEqualTo(1000);
    assertThat(n(p.get("totalCostPaise"))).isEqualTo(100000 + 12000 + 1000 + 1000 + 180);
    Map line = ((List<Map>) p.get("items")).getFirst();
    assertThat(n(line.get("gstPercent"))).isEqualTo(12);
    assertThat(line).containsEntry("hsn", "1905");
    assertThat(n(line.get("landedTotalPaise"))).as("composition: tax and freight are cost").isEqualTo(100000 + 12000 + 1000 + 1000 + 180);
  }

  @Test
  void discountsFreightRoundOffAndTheExpectedTotalAreWorkedOutOnTheServer() {
    String a = product(18, "");
    String b = product(18, "");
    var items = List.of(item(a, 1, 60000, Map.of()), item(b, 1, 40000, Map.of("discountPercent", 10)));
    Map ok = create(body(supplierName(), items, Map.of("docDiscountPaise", 1000, "freightPaise", 500, "roundToRupee", true)));
    assertThat(n(ok.get("lineDiscountPaise"))).isEqualTo(4000);
    assertThat(n(ok.get("docDiscountPaise"))).isEqualTo(1000);
    assertThat(ok.get("docDiscountPercent")).isNull();
    long total = n(ok.get("totalCostPaise"));
    assertThat(total % 100).isZero();
    assertThat(n(ok.get("roundOffPaise")) + n(ok.get("taxablePaise")) + n(ok.get("cgstPaise")) + n(ok.get("sgstPaise")) + n(ok.get("freightPaise")))
        .isEqualTo(total);

    var wrong = call(HttpMethod.POST, P, admin(), body(supplierName(), items, Map.of("docDiscountPercent", 5, "expectedTotalPaise", 1)), key());
    assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(wrong.getBody()).containsEntry("code", "TOTAL_MISMATCH");
    var pct = body(supplierName(), items, Map.of("docDiscountPercent", 5));
    Map first = create(pct);
    assertThat(n(first.get("docDiscountPaise"))).isEqualTo(Math.round((60000 + 36000) * 0.05));
    assertThat(((Number) first.get("docDiscountPercent")).doubleValue()).isEqualTo(5.0);
    pct.put("expectedTotalPaise", first.get("totalCostPaise"));
    assertThat(call(HttpMethod.POST, P, admin(), pct, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void badPurchasesAreRefusedAndMoveNoStock() {
    String it = product(18, "");
    Map<String, Object> good = item(it, 1, 1000, Map.of());
    List<Map<String, Object>> bodies = new ArrayList<>();
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("freeQty", -1))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("gstPercent", 7))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("discountPercent", 101))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("discountPercent", 1.234))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("discountPercent", 5, "discountPaise", 5))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("discountPaise", 1001))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("discountPaise", -1))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("discountPercent", -1))), Map.of()));
    bodies.add(body("S", List.of(good), Map.of("freightPaise", 1_000_000_000_000_000L)));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("hsn", "12"))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("batchNo", "x".repeat(41)))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("mrpPaise", 100, "sellingPricePaise", 200))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1, 1000, Map.of("applyToVariant", true))), Map.of()));
    bodies.add(body("S", List.of(item(it, 1_000_000, 1000, Map.of("freeQty", 1))), Map.of()));
    bodies.add(body("S", List.of(good), Map.of("invoiceDate", TODAY.plusDays(1).toString())));
    bodies.add(body("S", List.of(good), Map.of("receivedDate", TODAY.plusDays(1).toString())));
    bodies.add(body("S", List.of(good), Map.of("gstMode", "SOMETIMES")));
    bodies.add(body("S", List.of(good), Map.of("docDiscountPercent", 5, "docDiscountPaise", 5)));
    bodies.add(body("S", List.of(good), Map.of("docDiscountPaise", 1001)));
    bodies.add(body("S", List.of(good), Map.of("freightPaise", 100, "freightGstPercent", 9)));
    bodies.add(body("S", List.of(good), Map.of("freightPaise", -1)));
    bodies.add(body("S", List.of(good), Map.of("supplierGstin", "BAD")));
    bodies.add(body("S", List.of(good), Map.of("supplierPhone", "call me")));
    bodies.add(body("S", List.of(good), Map.of("supplierStateCode", "9")));
    bodies.add(body("S", List.of(good), Map.of("supplierGstin", "24ABCDE1234F1Z5", "supplierStateCode", "27")));
    bodies.add(body("S", List.of(good), Map.of("paymentStatus", "LATER")));
    bodies.add(body("S", List.of(good), Map.of("paymentStatus", "PENDING", "paidPaise", 10)));
    bodies.add(body("S", List.of(good), Map.of("paymentStatus", "PAID", "paidPaise", 10, "paymentMode", "CASH")));
    bodies.add(body("S", List.of(good), Map.of("paymentStatus", "PAID")));
    bodies.add(body("S", List.of(good), Map.of("paymentStatus", "PARTIAL", "paymentMode", "CASH")));
    bodies.add(body("S", List.of(good), Map.of("paymentStatus", "PARTIAL", "paidPaise", 1180, "paymentMode", "CASH")));
    bodies.add(body("S", List.of(good), Map.of("paidPaise", 99999, "paymentMode", "CASH")));
    bodies.add(body("S", List.of(good), Map.of("paidPaise", -5)));
    bodies.add(body("S", List.of(good), Map.of("paidPaise", 100, "paymentMode", "CASH", "paymentRef", "r".repeat(81))));
    bodies.add(body("S", List.of(good), Map.of("dueDate", TODAY.minusDays(1).toString(), "invoiceDate", TODAY.toString())));
    bodies.add(body("S", List.of(good), Map.of("attachmentNote", "a".repeat(501))));
    bodies.add(body("S", List.of(good), Map.of("supplierId", UUID.randomUUID().toString())));
    bodies.add(body(" ", List.of(good), Map.of()));
    for (Map<String, Object> bad : bodies) {
      var res = call(HttpMethod.POST, P, admin(), bad, key());
      assertThat(res.getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(res.getBody()).as(bad.toString()).containsKey("details");
    }
    assertThat(stock(it)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from supplier where name_key = 's'", Long.class)).isZero();
  }

  @Test
  void aPurchaseCanBePaidInPartsAndNeverOverpaid() {
    String it = product(0, "");
    Map p = create(body(supplierName(), List.of(item(it, 10, 1000, Map.of())),
        Map.of("invoiceDate", TODAY.minusDays(40).toString(), "dueDate", TODAY.minusDays(5).toString())));
    Object id = p.get("id");
    assertThat(p).containsEntry("paymentStatus", "PENDING").containsEntry("overdue", true);
    assertThat(n(p.get("duePaise"))).isEqualTo(10000);

    assertThat(((List<Map>) get(P + "?overdue=true&size=50", admin()).getBody().get("items")).stream().map(x -> x.get("id"))).contains(id);
    assertThat(((List<Map>) get(P + "?paymentStatus=PAID&supplierId=" + p.get("supplierId"), admin()).getBody().get("items"))).isEmpty();
    assertThat(((List<Map>) get(P + "?paymentStatus=pending&supplier=" + p.get("supplierName").toString().substring(12) + "&from=" + TODAY.minusDays(60) + "&to=" + TODAY, admin()).getBody()
        .get("items")).stream().map(x -> x.get("id"))).contains(id);
    assertThat(get(P + "?paymentStatus=NOPE", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get(P + "?from=" + TODAY + "&to=" + TODAY.minusDays(1), admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get(P + "?from=" + TODAY.minusDays(100), admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get(P + "?to=" + TODAY, admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
    var small = get(P + "?size=1", admin()).getBody();
    assertThat((List) small.get("items")).hasSize(1);
    assertThat(small).containsEntry("hasMore", true);
    assertThat(n(small.get("total"))).isGreaterThan(1);
    for (String url : List.of(P + "/" + id + "/payments", P + "/" + id + "/returns")) {
      assertThat(call(HttpMethod.POST, url, admin(), Map.of("amountPaise", 1, "mode", "CASH", "reason", "r", "items", List.of(Map.of("purchaseItemId", UUID.randomUUID().toString(), "qty", 1))), " ")
          .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    assertThat(call(HttpMethod.POST, P, admin(), body("S", List.of(item(product(0, ""), 1, 1, Map.of())), Map.of()), " ").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    var payables = get(P + "/payables", admin()).getBody();
    assertThat(n(payables.get("overdueCount"))).isGreaterThanOrEqualTo(1);
    assertThat(n(payables.get("totalPurchasedPaise")) - n(payables.get("totalPaidPaise"))).isEqualTo(n(payables.get("totalDuePaise")));
    assertThat(((List<Map>) payables.get("bySupplier")).stream().map(x -> x.get("supplierId"))).contains(p.get("supplierId"));

    String k = key();
    var first = pay(id, 4000, "cash", k);
    assertThat(status(first)).isEqualTo(HttpStatus.CREATED);
    Map afterFirst = (Map) first.get("body");
    assertThat(afterFirst).containsEntry("paymentStatus", "PARTIAL");
    assertThat(n(afterFirst.get("duePaise"))).isEqualTo(6000);
    assertThat(pay(id, 4000, "cash", k).get("body")).as("a repeat returns the first answer").isEqualTo(afterFirst);
    assertThat(code(pay(id, 5000, "cash", k))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(((List) get(P + "/" + id, admin()).getBody().get("payments"))).as("the repeat added nothing").hasSize(1);

    var over = pay(id, 6001, "BANK", key());
    assertThat(status(over)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(code(over)).isEqualTo("PAYMENT_EXCEEDS_DUE");
    assertThat(n(((Map) over.get("body")).get("details") == null ? 6000 : ((Map) ((Map) over.get("body")).get("details")).get("duePaise"))).isEqualTo(6000);
    assertThat(status(pay(id, 0, "BANK", key()))).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(status(pay(id, 10, "BARTER", key()))).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, P + "/" + id + "/payments", admin(), Map.of("amountPaise", 10, "mode", "CASH"), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, P + "/" + id + "/payments", admin(), Map.of("amountPaise", 10, "mode", "CASH", "reference", "r".repeat(81)), key())
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, P + "/" + id + "/payments", admin(),
        Map.of("amountPaise", 10, "mode", "CASH", "paidAt", java.time.Instant.now().plusSeconds(86400).toString()), key()).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(status(pay(UUID.randomUUID(), 10, "CASH", key()))).isEqualTo(HttpStatus.NOT_FOUND);

    UUID account = UUID.randomUUID();
    var last = call(HttpMethod.POST, P + "/" + id + "/payments", admin(),
        Map.of("amountPaise", 6000, "mode", "CHEQUE", "reference", "000123", "note", "final", "moneyAccountId", account.toString(),
            "paidAt", java.time.Instant.now().minusSeconds(3600).toString()), key());
    assertThat(last.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(last.getBody()).containsEntry("paymentStatus", "PAID").containsEntry("overdue", false);
    assertThat(((List<Map>) last.getBody().get("payments")).stream().filter(x -> "CHEQUE".equals(x.get("mode"))).findFirst().orElseThrow()).containsEntry("moneyAccountId", account.toString()).containsEntry("mode", "CHEQUE");
    var done = pay(id, 1, "CASH", key());
    assertThat(status(done)).isEqualTo(HttpStatus.CONFLICT);
    assertThat(code(done)).isEqualTo("ALREADY_PAID");
  }

  @Test
  void otherValidCombinationsAreAccepted() {
    String it = product(0, "");
    Map inter = create(body(supplierName(), List.of(item(it, 1, 1000, Map.of("gstPercent", 18, "sellingPricePaise", 9000))),
        Map.of("supplierStateCode", "27", "paidPaise", 100, "paymentMode", "CASH")));
    assertThat(inter).containsEntry("interState", true).containsEntry("supplierStateCode", "27").containsEntry("paymentStatus", "PARTIAL");
    assertThat(n(inter.get("igstPaise"))).isEqualTo(180);
    assertThat(((List<Map>) inter.get("items")).getFirst().get("sellingPricePaise")).isEqualTo(9000);
    String mrpOnly = product(0, "");
    create(body(supplierName(), List.of(item(mrpOnly, 1, 1000, Map.of("mrpPaise", 25000, "applyToVariant", true))), Map.of()));
    assertThat(n(get("/api/v1/admin/products/" + mrpOnly, admin()).getBody().get("mrpPaise"))).isEqualTo(25000);
    String priceOnly = product(0, "");
    create(body(supplierName(), List.of(item(priceOnly, 1, 1000, Map.of("sellingPricePaise", 17000, "applyToVariant", true))), Map.of()));
    assertThat(n(get("/api/v1/admin/products/" + priceOnly, admin()).getBody().get("pricePaise"))).isEqualTo(17000);
  }

  @Test
  void aPurchaseRecordedAsPaidHasItsFirstPaymentRow() {
    String it = product(0, "");
    Map p = create(body(supplierName(), List.of(item(it, 2, 500, Map.of())), Map.of("paymentStatus", "PAID", "paymentMode", "cash")));
    assertThat(p).containsEntry("paymentStatus", "PAID");
    assertThat(n(p.get("paidPaise"))).isEqualTo(1000);
    assertThat((List) p.get("payments")).hasSize(1);
    Map inferred = create(body(supplierName(), List.of(item(it, 2, 500, Map.of())), Map.of("paidPaise", 1000, "paymentMode", "UPI")));
    assertThat(inferred).containsEntry("paymentStatus", "PAID");
    Map free = create(body(supplierName(), List.of(item(it, 2, 0, Map.of())), Map.of("paymentStatus", "PAID")));
    assertThat(free).containsEntry("paymentStatus", "PAID");
    assertThat((List) free.get("payments")).isEmpty();
  }

  @Test
  void dueDateComesFromTheSuppliersPaymentTerms() {
    var s = post("/api/v1/admin/suppliers", admin(), Map.of("name", supplierName(), "paymentTermsDays", 15));
    assertThat(s.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String it = product(0, "");
    Map p = create(Map.of("supplierId", s.getBody().get("id"), "items", List.of(item(it, 1, 100, Map.of())), "invoiceDate", TODAY.minusDays(3).toString()));
    assertThat(p.get("dueDate")).isEqualTo(TODAY.plusDays(12).toString());
    assertThat(p.get("supplierName")).isEqualTo(s.getBody().get("name"));
  }

  @Test
  void aReturnTakesStockOutCreditsTheSupplierAndReversesTheGst() {
    String it = product(18, "");
    String name = supplierName();
    Map p = create(body(name, List.of(item(it, 10, 10000, Map.of("freeQty", 2))), Map.of("paymentStatus", "PAID", "paymentMode", "BANK")));
    Object lineId = ((List<Map>) p.get("items")).getFirst().get("id");
    var gstBefore = get("/api/v1/admin/reports/purchases/input-gst", admin()).getBody();
    String k = key();
    Map<String, Object> req = Map.of("items", List.of(Map.of("purchaseItemId", lineId, "qty", 3)), "reason", "damaged");
    var r1 = call(HttpMethod.POST, P + "/" + p.get("id") + "/returns", admin(), req, k);
    assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat((String) r1.getBody().get("noteNo")).startsWith("DN-");
    assertThat(n(r1.getBody().get("taxablePaise"))).isEqualTo(25000);
    assertThat(n(r1.getBody().get("cgstPaise"))).isEqualTo(2250);
    assertThat(n(r1.getBody().get("totalPaise"))).isEqualTo(29500);
    assertThat(stock(it)).isEqualTo(9);
    assertThat(call(HttpMethod.POST, P + "/" + p.get("id") + "/returns", admin(), req, k).getBody()).as("a repeat").isEqualTo(r1.getBody());
    assertThat(stock(it)).isEqualTo(9);
    assertThat(call(HttpMethod.POST, P + "/" + p.get("id") + "/returns", admin(), Map.of("items", List.of(Map.of("purchaseItemId", lineId, "qty", 4)),
        "reason", "other"), k).getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");

    var purchase = get(P + "/" + p.get("id"), admin()).getBody();
    assertThat(n(purchase.get("returnedPaise"))).isEqualTo(29500);
    assertThat(n(purchase.get("duePaise"))).as("fully paid, then credited: the supplier owes the shop").isEqualTo(-29500);
    assertThat(purchase).containsEntry("paymentStatus", "PAID");
    assertThat(n(((List<Map>) purchase.get("items")).getFirst().get("returnedQty"))).isEqualTo(3);
    assertThat(status(pay(p.get("id"), 1, "CASH", key()))).isEqualTo(HttpStatus.CONFLICT);

    var gstAfter = get("/api/v1/admin/reports/purchases/input-gst", admin()).getBody();
    assertThat(n(gstAfter.get("cgstPaise")) - n(gstBefore.get("cgstPaise"))).isEqualTo(-2250);

    var rest = call(HttpMethod.POST, P + "/" + p.get("id") + "/returns", admin(), Map.of("items", List.of(Map.of("purchaseItemId", lineId, "qty", 9)),
        "reason", "rest"), key());
    assertThat(rest.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(n(rest.getBody().get("totalPaise"))).as("the last units take the exact remainder").isEqualTo(118000 - 29500);
    assertThat(stock(it)).isZero();
    var tooMany = call(HttpMethod.POST, P + "/" + p.get("id") + "/returns", admin(), Map.of("items", List.of(Map.of("purchaseItemId", lineId, "qty", 1)),
        "reason", "x"), key());
    assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(tooMany.getBody()).containsEntry("code", "RETURN_EXCEEDS_RECEIVED");

    assertThat(get("/api/v1/admin/purchase-returns/" + r1.getBody().get("id"), admin()).getBody()).isEqualTo(r1.getBody());
    assertThat(get("/api/v1/admin/purchase-returns/" + UUID.randomUUID(), admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var list = get("/api/v1/admin/purchases/" + p.get("id") + "/returns", admin()).getBody();
    assertThat((List<Map>) list.get("items")).extracting(x -> x.get("reason")).containsExactly("rest", "damaged");
    assertThat(get("/api/v1/admin/purchases/" + UUID.randomUUID() + "/returns", admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var unfiltered = get("/api/v1/admin/purchase-returns?size=1", admin()).getBody();
    assertThat(unfiltered).containsEntry("hasMore", true);
    assertThat(n(unfiltered.get("total"))).isGreaterThan(1);
    var reg = get("/api/v1/admin/reports/purchases/register?size=1", admin()).getBody();
    assertThat(reg).containsEntry("hasMore", true);
    String untouched = product(0, "");
    Map clean = create(body(supplierName(), List.of(item(untouched, 1, 1, Map.of())), Map.of()));
    assertThat((List) get(P + "/" + clean.get("id") + "/returns", admin()).getBody().get("items")).isEmpty();
    var all = get("/api/v1/admin/purchase-returns?supplierId=" + p.get("supplierId") + "&from=" + TODAY + "&to=" + TODAY, admin()).getBody();
    assertThat((List) all.get("items")).hasSize(2);
    assertThat(get("/api/v1/admin/purchase-returns?from=" + TODAY + "&to=" + TODAY.minusDays(2), admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void badReturnsAreRefused() {
    String it = product(0, "");
    Map p = create(body(supplierName(), List.of(item(it, 5, 100, Map.of())), Map.of()));
    Object lineId = ((List<Map>) p.get("items")).getFirst().get("id");
    String url = P + "/" + p.get("id") + "/returns";
    Map line = Map.of("purchaseItemId", lineId, "qty", 1);
    assertThat(call(HttpMethod.POST, url, admin(), Map.of("items", List.of(line), "reason", "r"), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("items", List.of(line), "reason", " "), Map.of("items", List.of(), "reason", "r"),
        Map.of("items", List.of(line, line), "reason", "r"), Map.of("items", List.of(Map.of("purchaseItemId", UUID.randomUUID().toString(), "qty", 1)), "reason", "r"),
        Map.of("items", List.of(Map.of("purchaseItemId", lineId, "qty", 0)), "reason", "r"))) {
      assertThat(call(HttpMethod.POST, url, admin(), bad, key()).getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    assertThat(call(HttpMethod.POST, P + "/" + UUID.randomUUID() + "/returns", admin(), Map.of("items", List.of(line), "reason", "r"), key()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    jdbc.update("update inventory set qty_on_hand = 2 where product_id = ?::uuid", it);
    var short1 = call(HttpMethod.POST, url, admin(), Map.of("items", List.of(Map.of("purchaseItemId", lineId, "qty", 3)), "reason", "r"), key());
    assertThat(short1.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(short1.getBody()).containsEntry("code", "INSUFFICIENT_STOCK_FOR_RETURN");
    assertThat(stock(it)).isEqualTo(2);
    assertThat(n(get(P + "/" + p.get("id"), admin()).getBody().get("returnedPaise"))).isZero();
  }

  @Test
  void reportsShowTheRegisterInputGstDiscountsAndAgeing() {
    String it = product(18, "");
    String name = supplierName();
    Map p = create(body(name, List.of(item(it, 10, 10000, Map.of("discountPaise", 5000))),
        Map.of("invoiceDate", TODAY.minusDays(70).toString(), "freightPaise", 1000, "freightGstPercent", 18, "docDiscountPaise", 2000)));
    Object supplier = p.get("supplierId");
    String range = "from=" + TODAY.minusDays(80) + "&to=" + TODAY.minusDays(60);

    var reg = get("/api/v1/admin/reports/purchases/register?" + range + "&supplierId=" + supplier + "&productId=" + it + "&gstRate=18", admin()).getBody();
    assertThat((List<Map>) reg.get("items")).singleElement().satisfies(r -> {
      assertThat(r).containsEntry("supplierName", name).containsEntry("productName", get("/api/v1/admin/products/" + it, admin()).getBody().get("nameEn"));
      assertThat(n(r.get("discountPaise"))).isEqualTo(7000);
      assertThat(n(r.get("taxablePaise"))).isEqualTo(93000);
    });
    assertThat((List) get("/api/v1/admin/reports/purchases/register?" + range + "&supplierId=" + supplier + "&gstRate=5", admin()).getBody().get("items")).isEmpty();

    var gst = get("/api/v1/admin/reports/purchases/input-gst?" + range, admin()).getBody();
    var rows = (List<Map>) gst.get("lines");
    assertThat(rows).anySatisfy(r -> assertThat(r).containsEntry("gstMode", "REGULAR").containsEntry("gstPercent", 18));
    assertThat(n(gst.get("creditablePaise"))).isEqualTo(n(gst.get("cgstPaise")) + n(gst.get("sgstPaise")) + n(gst.get("igstPaise")) + n(gst.get("cessPaise")));
    assertThat(n(gst.get("taxablePaise"))).isGreaterThanOrEqualTo(93000 + 1000);

    var disc = get("/api/v1/admin/reports/purchases/discounts?" + range, admin()).getBody();
    assertThat((List<Map>) disc.get("bySupplier")).anySatisfy(r -> assertThat(r).containsEntry("supplierName", name));
    assertThat(n(disc.get("docDiscountPaise"))).isGreaterThanOrEqualTo(2000);

    var ageing = get("/api/v1/admin/reports/purchases/ageing", admin()).getBody();
    Map mine = ((List<Map>) ageing.get("bySupplier")).stream().filter(r -> name.equals(r.get("supplierName"))).findFirst().orElseThrow();
    assertThat(n(mine.get("days61PlusPaise"))).isEqualTo(n(p.get("totalCostPaise")));
    assertThat(n(mine.get("days0to30Paise"))).isZero();
    var old = get("/api/v1/admin/reports/purchases/ageing?asOf=" + TODAY.minusDays(60), admin()).getBody();
    Map then = ((List<Map>) old.get("bySupplier")).stream().filter(r -> name.equals(r.get("supplierName"))).findFirst().orElseThrow();
    assertThat(n(then.get("days0to30Paise"))).isEqualTo(n(p.get("totalCostPaise")));

    assertThat(get("/api/v1/admin/reports/purchases/input-gst?from=" + TODAY + "&to=" + TODAY.minusDays(1), admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/reports/purchases/discounts?from=" + TODAY.minusDays(400), admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/reports/purchases/register", admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void theSupplierMasterCanBeManagedAndSearched() {
    String name = supplierName();
    String k = key();
    Map<String, Object> body = Map.of("name", "  " + name.toUpperCase() + "  ", "gstin", "24abcde1234f1z5", "phone", "+91 98250 22222", "address", "Rajkot",
        "paymentTermsDays", 30);
    var created = call(HttpMethod.POST, "/api/v1/admin/suppliers", admin(), body, k);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).containsEntry("gstin", "24ABCDE1234F1Z5").containsEntry("stateCode", "24").containsEntry("active", true);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/suppliers", admin(), body, k).getBody().get("id")).isEqualTo(created.getBody().get("id"));
    Object id = created.getBody().get("id");
    var dup = post("/api/v1/admin/suppliers", admin(), Map.of("name", name.toLowerCase()));
    assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(dup.getBody()).containsEntry("code", "SUPPLIER_EXISTS");

    for (String q : List.of(name.substring(12), "22222", "24abcde1234")) {
      assertThat(((List<Map>) get("/api/v1/admin/suppliers?size=50&q=" + q, admin()).getBody().get("items")))
          .extracting(x -> x.get("id")).contains(id);
    }
    assertThat((List) get("/api/v1/admin/suppliers?q=%25%5F", admin()).getBody().get("items")).isEmpty();
    assertThat(get("/api/v1/admin/suppliers?q=", admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
    var one = get("/api/v1/admin/suppliers/" + id, admin()).getBody();
    assertThat(n(one.get("purchaseCount"))).isZero();
    assertThat(one.get("lastPurchaseAt")).isNull();

    String other = supplierName();
    var otherId = post("/api/v1/admin/suppliers", admin(), Map.of("name", other)).getBody().get("id");
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/suppliers/" + otherId, admin(), Map.of("name", name), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    var renamed = call(HttpMethod.PUT, "/api/v1/admin/suppliers/" + id, admin(), Map.of("name", name + " Ltd", "stateCode", "24", "gstin", "24ABCDE1234F1Z5"), null);
    assertThat(renamed.getBody()).containsEntry("name", name + " Ltd").containsEntry("phone", "");
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/suppliers/" + UUID.randomUUID(), admin(), Map.of("name", "x"), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("name", " "), Map.of("name", "n", "gstin", "nope"), Map.of("name", "n", "stateCode", "7"),
        Map.of("name", "n", "stateCode", "27", "gstin", "24ABCDE1234F1Z5"), Map.of("name", "n", "phone", "abc"), Map.of("name", "n", "paymentTermsDays", 400),
        Map.of("name", "x".repeat(121)), Map.of("name", "n", "address", "a".repeat(301)))) {
      assertThat(post("/api/v1/admin/suppliers", admin(), bad).getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    assertThat(post("/api/v1/admin/suppliers/" + id + "/deactivate", admin(), null).getBody()).containsEntry("active", false);
    assertThat(post("/api/v1/admin/suppliers/" + id + "/deactivate", admin(), null).getBody()).containsEntry("active", false);
    String it = product(0, "");
    assertThat(call(HttpMethod.POST, P, admin(), Map.of("supplierId", id, "items", List.of(item(it, 1, 1, Map.of()))), key()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, P, admin(), body(name + " Ltd", List.of(item(it, 1, 1, Map.of())), Map.of()), key()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(((List<Map>) get("/api/v1/admin/suppliers?active=true&q=" + name.substring(12), admin()).getBody().get("items"))).isEmpty();
    assertThat(post("/api/v1/admin/suppliers/" + id + "/activate", admin(), null).getBody()).containsEntry("active", true);
    assertThat(post("/api/v1/admin/suppliers/" + UUID.randomUUID() + "/activate", admin(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/suppliers/" + UUID.randomUUID(), admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    var p = create(body(name + "  LTD", List.of(item(it, 1, 1, Map.of())), Map.of("supplierPhone", "1111", "supplierGstin", "27ABCDE1234F1Z5")));
    assertThat(p.get("supplierId")).as("matched by trimmed, case-insensitive name").isEqualTo(id);
    assertThat(p.get("supplierPhone")).as("a missing phone is filled in").isEqualTo("1111");
    assertThat(p.get("supplierGstin")).as("a stored GSTIN is never overwritten").isEqualTo("24ABCDE1234F1Z5");
    var pagedAll = get("/api/v1/admin/suppliers?page=0&size=1", admin()).getBody();
    assertThat(pagedAll).containsKeys("totalExact", "hasMore");
  }

  @Test
  void aRepeatedKeyAndOldClientsStillWork() {
    String it = product(0, "");
    String name = supplierName();
    String k = key();
    Map<String, Object> body = body(name, List.of(item(it, 3, 100, Map.of())), Map.of("invoiceNo", "R-1"));
    var a = call(HttpMethod.POST, P, admin(), body, k);
    var b = call(HttpMethod.POST, P, admin(), body, k);
    assertThat(b.getBody()).isEqualTo(a.getBody());
    assertThat(stock(it)).isEqualTo(3);
    Map<String, Object> changed = body(name, List.of(item(it, 3, 100, Map.of("freeQty", 1))), Map.of("invoiceNo", "R-1"));
    assertThat(call(HttpMethod.POST, P, admin(), changed, k).getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");

    String legacy = key();
    jdbc.update("update purchase set request_hash = null where idem_key = ?", k);
    assertThat(call(HttpMethod.POST, P, admin(), body, k).getBody().get("id")).isEqualTo(a.getBody().get("id"));
    assertThat(call(HttpMethod.POST, P, admin(), body(name, List.of(item(it, 3, 100, Map.of())), Map.of("invoiceNo", "R-2")), k).getBody())
        .containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    assertThat(call(HttpMethod.POST, P, admin(), body(supplierName(), List.of(item(it, 3, 100, Map.of())), Map.of("invoiceNo", "R-1")), k).getBody())
        .containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    assertThat(legacy).isNotBlank();
  }

  @Test
  void onlyTheAdminMayUseThePurchaseEndpoints() {
    String cashier = token("cashier@vijaysupermall.local");
    for (String url : List.of("/api/v1/admin/suppliers", P + "/payables", "/api/v1/admin/purchase-returns", "/api/v1/admin/reports/purchases/ageing")) {
      assertThat(get(url, cashier).getStatusCode()).as(url).isEqualTo(HttpStatus.FORBIDDEN);
    }
    assertThat(post(P + "/" + UUID.randomUUID() + "/payments", cashier, Map.of("amountPaise", 1, "mode", "CASH")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  /*
   * 10 x 100.00 = 1,00,000 paise taxable. At 1.5% the GST is 1500 (750 + 750), at 0.25% it is 250 (125 + 125), at 40% it is 40000
   * (20000 + 20000). Rates come back as JSON numbers without needless zeros.
   */
  @Test
  void fractionalGstRatesWorkInEveryGstModeAndShowInTheReports() {
    String quarter = product(new BigDecimal("0.25"), "1001");
    String half = product(new BigDecimal("1.5"), "1002");
    String forty = product(40, "1003");
    String date = TODAY.minusDays(150).toString();

    assertThat(get(P + "/lookup?productId=" + half, admin()).getBody().get("gstPercent")).isEqualTo(1.5);
    assertThat(get(P + "/lookup?productId=" + forty, admin()).getBody().get("gstPercent")).isEqualTo(40);

    Map regular = create(body(supplierName(), List.of(item(half, 10, 10000, Map.of()), item(quarter, 10, 10000, Map.of("gstPercent", 0.25)),
        item(forty, 10, 10000, Map.of("gstPercent", 40.0))), Map.of("invoiceDate", date, "freightPaise", 1000, "freightGstPercent", 5)));
    List<Map> lines = (List<Map>) regular.get("items");
    assertThat(lines.stream().map(l -> l.get("gstPercent"))).containsExactlyInAnyOrder(1.5, 0.25, 40);
    assertThat(lines.stream().map(l -> n(l.get("cgstPaise")))).containsExactlyInAnyOrder(750L, 125L, 20000L);
    assertThat(lines.stream().map(l -> n(l.get("sgstPaise")))).containsExactlyInAnyOrder(750L, 125L, 20000L);
    assertThat(regular.get("freightGstPercent")).isEqualTo(5);
    assertThat(n(regular.get("freightGstPaise"))).isEqualTo(50);
    assertThat(n(regular.get("totalCostPaise"))).isEqualTo(300000 + 41750 + 1000 + 50);
    assertThat(lines.stream().map(l -> n(l.get("landedTotalPaise")))).containsExactlyInAnyOrder(100334L, 100333L, 100333L);

    String comp = product(new BigDecimal("1.5"), "");
    Map composition = create(body(supplierName(), List.of(item(comp, 10, 10000, Map.of())), Map.of("gstMode", "COMPOSITION")));
    assertThat(n(((List<Map>) composition.get("items")).getFirst().get("landedTotalPaise"))).isEqualTo(101500);

    String none = product(40, "");
    Map noGst = create(body(supplierName(), List.of(item(none, 10, 10000, Map.of("gstPercent", 40))), Map.of("gstMode", "NONE")));
    assertThat(n(noGst.get("totalCostPaise"))).isEqualTo(100000);
    assertThat(n(noGst.get("cgstPaise"))).isZero();

    var shown = get(P + "/" + regular.get("id"), admin()).getBody();
    assertThat(((List<Map>) shown.get("items")).stream().map(l -> l.get("gstPercent"))).containsExactlyInAnyOrder(1.5, 0.25, 40);
    assertThat(jdbc.queryForObject("select gst_percent from purchase_item where purchase_id = ?::uuid and product_id = ?::uuid", BigDecimal.class,
        regular.get("id"), half)).isEqualByComparingTo("1.5");

    String range = "from=" + date + "&to=" + date;
    var reg = get("/api/v1/admin/reports/purchases/register?" + range + "&productId=" + half + "&gstRate=1.50", admin()).getBody();
    assertThat((List<Map>) reg.get("items")).singleElement().satisfies(r -> assertThat(r).containsEntry("gstPercent", 1.5));
    var gst = get("/api/v1/admin/reports/purchases/input-gst?" + range, admin()).getBody();
    List<Map> rows = (List<Map>) gst.get("lines");
    assertThat(rows).anySatisfy(r -> assertThat(r).containsEntry("gstMode", "REGULAR").containsEntry("gstPercent", 0.25).containsEntry("cgstPaise", 125));
    assertThat(rows).anySatisfy(r -> assertThat(r).containsEntry("gstPercent", 40).containsEntry("taxablePaise", 100000));
    assertThat(rows).anySatisfy(r -> assertThat(r).containsEntry("gstPercent", 1.5).containsEntry("gstMode", "REGULAR"));
  }

  @Test
  void ratesOutsideTheAllowedSetAreRefusedWithTheFieldAndTheFixedText() {
    String it = product(18, "");
    for (Object bad : new Object[] {18.5, 1, 0.5, 7, 41}) {
      var res = call(HttpMethod.POST, P, admin(), body("S", List.of(item(it, 1, 1000, Map.of("gstPercent", bad))), Map.of()), key());
      assertThat(res.getStatusCode()).as(String.valueOf(bad)).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(((Map) ((Map) res.getBody().get("details")).get("fields")).get("gstPercent"))
          .isEqualTo("must be one of 0, 0.25, 1.5, 3, 5, 12, 18, 28 or 40");
    }
  }
}
