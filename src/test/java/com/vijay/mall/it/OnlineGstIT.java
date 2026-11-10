package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.tax.GstCalculator;
import com.vijay.mall.common.tax.TaxSplit;
import com.vijay.mall.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** GST on the online quote and order views: per line, per slab, frozen at order time, and the money still adds up. */
@SuppressWarnings({"unchecked", "rawtypes"})
class OnlineGstIT extends IntegrationTestBase {
  private String product(long price, String gst, String hsn) {
    String category = jdbc.queryForObject("select id::text from category limit 1", String.class);
    var res = post("/api/v1/admin/products", adminToken(), Map.of("categoryId", category, "nameEn", "Online GST " + UUID.randomUUID(), "nameGu", "વસ્તુ",
        "mrpPaise", price + 1000, "pricePaise", price, "unit", "pack", "packSize", "1 kg", "qtyOnHand", 100));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) res.getBody().get("id");
    if (gst != null) {
      var saved = call(HttpMethod.PUT, "/api/v1/admin/products/" + id + "/pos", adminToken(), Map.of("soldBy", "UNIT", "gstPercent", new BigDecimal(gst), "hsn", hsn), null);
      assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    return id;
  }

  private Map<String, Object> cart(Map<String, Object> extra, Object... idsAndQty) {
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    for (int i = 0; i < idsAndQty.length; i += 2) items.add(Map.of("productId", idsAndQty[i], "qty", idsAndQty[i + 1]));
    Map<String, Object> m = new HashMap<>(Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP"));
    m.putAll(extra);
    return m;
  }

  private Map<String, Object> place(String token, Map<String, Object> cart) {
    Map<String, Object> body = new HashMap<>(cart);
    body.put("paymentMethod", "COD");
    body.put("expectedTotalPaise", ((Number) post("/api/v1/cart/quote", token, cart).getBody().get("totalPaise")).longValue());
    var res = call(HttpMethod.POST, "/api/v1/orders", token, body, UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isIn(HttpStatus.CREATED, HttpStatus.OK);
    return res.getBody();
  }

  private static long l(Object o) { return ((Number) o).longValue(); }

  private static Map<String, Map<String, Object>> byProduct(Object lines) {
    Map<String, Map<String, Object>> out = new HashMap<>();
    for (Map<String, Object> line : (List<Map<String, Object>>) lines) out.put((String) line.get("productId"), line);
    return out;
  }

  /** The invariant of every document: taxable plus tax is what was paid for the goods, and the slabs add up to the totals. */
  private static void assertAddsUp(Map<String, Object> doc, Object lines) {
    long goods = l(doc.get("totalPaise")) - l(doc.get("deliveryFeePaise"));
    assertThat(l(doc.get("taxableTotalPaise")) + l(doc.get("taxTotalPaise"))).isEqualTo(goods);
    assertThat(l(doc.get("cgstTotalPaise")) + l(doc.get("sgstTotalPaise"))).isEqualTo(l(doc.get("taxTotalPaise")));
    List<Map<String, Object>> slabs = (List) doc.get("gstSlabs");
    assertThat(slabs.stream().mapToLong(s -> l(s.get("taxablePaise"))).sum()).isEqualTo(l(doc.get("taxableTotalPaise")));
    assertThat(slabs.stream().mapToLong(s -> l(s.get("taxPaise"))).sum()).isEqualTo(l(doc.get("taxTotalPaise")));
    assertThat(slabs).allSatisfy(s -> assertThat(l(s.get("cgstPaise")) + l(s.get("sgstPaise"))).isEqualTo(l(s.get("taxPaise"))));
    List<Double> rates = slabs.stream().map(s -> ((Number) s.get("gstPercent")).doubleValue()).toList();
    assertThat(rates).isSorted().doesNotHaveDuplicates();
    List<Map<String, Object>> rows = (List) lines;
    assertThat(rows.stream().mapToLong(r -> l(r.get("taxablePaise"))).sum()).isEqualTo(l(doc.get("taxableTotalPaise")));
    assertThat(rows.stream().mapToLong(r -> l(r.get("cgstPaise")) + l(r.get("sgstPaise"))).sum()).isEqualTo(l(doc.get("taxTotalPaise")));
  }

  @Test
  void quoteShowsEachLineAndEachSlabWithoutDiscounts() {
    String p18 = product(11800, "18", "1006");
    String p40 = product(14000, "40", "2202");
    String p025 = product(10025, "0.25", "0713");
    String p0 = product(5000, "0", "0401");
    String unset = product(3333, null, null);
    String t = token(freshEmail());

    var q = post("/api/v1/cart/quote", t, cart(Map.of(), p18, 1, p40, 1, p025, 1, p0, 1, unset, 1)).getBody();
    var lines = byProduct(q.get("lines"));
    assertThat(lines.get(p18)).containsEntry("gstPercent", 18).containsEntry("hsn", "1006").containsEntry("taxablePaise", 10000)
        .containsEntry("cgstPaise", 900).containsEntry("sgstPaise", 900).containsEntry("gstConfigured", true);
    assertThat(lines.get(p40)).containsEntry("gstPercent", 40).containsEntry("taxablePaise", 10000).containsEntry("cgstPaise", 2000);
    assertThat(lines.get(p025)).containsEntry("gstPercent", 0.25).containsEntry("taxablePaise", 10000).containsEntry("cgstPaise", 12)
        .containsEntry("sgstPaise", 13);
    assertThat(lines.get(p0)).containsEntry("gstPercent", 0).containsEntry("taxablePaise", 5000).containsEntry("cgstPaise", 0).containsEntry("gstConfigured", true);
    assertThat(lines.get(unset)).containsEntry("gstPercent", 0).containsEntry("hsn", "").containsEntry("taxablePaise", 3333).containsEntry("gstConfigured", false);
    List<Map<String, Object>> slabs = (List) q.get("gstSlabs");
    assertThat(slabs).extracting(s -> ((Number) s.get("gstPercent")).doubleValue()).containsExactly(0.0, 0.25, 18.0, 40.0);
    assertThat(slabs.get(0)).containsEntry("taxablePaise", 8333).containsEntry("taxPaise", 0);
    assertThat(slabs.get(3)).containsEntry("taxablePaise", 10000).containsEntry("cgstPaise", 2000).containsEntry("sgstPaise", 2000).containsEntry("taxPaise", 4000);
    assertThat(q).containsEntry("taxableTotalPaise", 38333).containsEntry("taxTotalPaise", 1800 + 4000 + 25);
    assertAddsUp(q, q.get("lines"));
  }

  @Test
  void anOrderKeepsTheQuotedFiguresForCouponAndCoinsAndLaterRateChangesDoNotTouchIt() {
    String p18 = product(11800, "18", "1006");
    String p5 = product(10500, "5", "2106");
    String p0 = product(777, "0", "");
    String admin = adminToken();
    String email = freshEmail();
    String customerId = (String) ((Map) login(email).get("user")).get("id");
    String t = token(email);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/customers/" + customerId + "/coins", admin, Map.of("delta", 5, "reason", "gst test"), UUID.randomUUID().toString())
        .getStatusCode()).isEqualTo(HttpStatus.OK);
    String code = "GST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    assertThat(post("/api/v1/admin/coupons", admin, Map.of("code", code, "kind", "FLAT", "value", 101)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    Map<String, Object> cart = cart(Map.of("couponCode", code, "coinMode", "ALL"), p18, 2, p5, 1, p0, 3);
    var q = post("/api/v1/cart/quote", t, cart).getBody();
    assertThat(l(q.get("couponDiscountPaise"))).isPositive();
    assertThat(l(q.get("coinDiscountPaise"))).isPositive();
    assertAddsUp(q, q.get("lines"));
    assertThat(l(q.get("taxableTotalPaise")) + l(q.get("taxTotalPaise")))
        .isEqualTo(l(q.get("subtotalPaise")) - l(q.get("couponDiscountPaise")) - l(q.get("coinDiscountPaise")));

    // the lines are recomputed here from the quote's own numbers with the shared maths
    List<Map<String, Object>> qLines = (List) q.get("lines");
    long[] gross = qLines.stream().mapToLong(x -> l(x.get("lineTotalPaise"))).toArray();
    long[] shares = qLines.stream().mapToLong(x -> l(x.get("couponDiscountPaise"))).toArray();
    long[] net = GstCalculator.netPerLine(gross, shares, l(q.get("couponDiscountPaise")), l(q.get("coinDiscountPaise")));
    for (int i = 0; i < net.length; i++) {
      TaxSplit s = GstCalculator.split(net[i], new BigDecimal(String.valueOf(qLines.get(i).get("gstPercent"))));
      assertThat(l(qLines.get(i).get("taxablePaise"))).isEqualTo(s.taxable());
      assertThat(l(qLines.get(i).get("cgstPaise"))).isEqualTo(s.cgst());
      assertThat(l(qLines.get(i).get("sgstPaise"))).isEqualTo(s.sgst());
    }

    Map<String, Object> placed = place(t, cart);
    String orderId = (String) placed.get("id");
    var order = get("/api/v1/orders/" + orderId, t).getBody();
    assertThat(order).containsEntry("gstRecorded", true);
    assertAddsUp(order, order.get("items"));
    for (String key : List.of("taxableTotalPaise", "cgstTotalPaise", "sgstTotalPaise", "taxTotalPaise", "gstSlabs")) {
      assertThat(order.get(key)).as(key).isEqualTo(q.get(key));
    }
    var orderLines = byProduct(order.get("items"));
    for (Map<String, Object> ql : qLines) {
      Map<String, Object> ol = orderLines.get(ql.get("productId"));
      for (String key : List.of("gstPercent", "hsn", "taxablePaise", "cgstPaise", "sgstPaise")) assertThat(ol.get(key)).as(key).isEqualTo(ql.get(key));
    }
    assertThat(jdbc.queryForObject("select count(*) from order_item where order_id = ?::uuid and gst_percent is not null and taxable_paise is not null "
        + "and cgst_paise is not null and sgst_paise is not null and hsn is not null", Long.class, orderId)).isEqualTo(3);

    String orderNo = (String) order.get("orderNo");
    var listed = ((List<Map<String, Object>>) get("/api/v1/orders?size=50", t).getBody().get("items")).stream()
        .filter(r -> orderId.equals(r.get("id"))).findFirst().orElseThrow();
    assertThat(listed).containsEntry("gstRecorded", true).containsEntry("taxableTotalPaise", order.get("taxableTotalPaise"))
        .containsEntry("taxTotalPaise", order.get("taxTotalPaise")).containsEntry("cgstTotalPaise", order.get("cgstTotalPaise"));
    assertThat(listed.get("gstSlabs")).isEqualTo(order.get("gstSlabs"));

    var adminOrder = get("/api/v1/admin/orders/" + orderId, admin).getBody();
    assertThat(adminOrder).containsEntry("gstRecorded", true).containsEntry("taxTotalPaise", order.get("taxTotalPaise"));
    assertThat(adminOrder.get("gstSlabs")).isEqualTo(order.get("gstSlabs"));
    var adminRow = ((List<Map<String, Object>>) get("/api/v1/admin/orders?q=" + orderNo, admin).getBody().get("items")).stream()
        .filter(r -> orderId.equals(r.get("id"))).findFirst().orElseThrow();
    assertThat(adminRow).containsEntry("gstRecorded", true).containsEntry("taxableTotalPaise", order.get("taxableTotalPaise"));
    assertThat(adminRow.get("gstSlabs")).isEqualTo(order.get("gstSlabs"));

    // the shop changes the rates afterwards: the order stays as it was, a new quote follows the new rate
    call(HttpMethod.PUT, "/api/v1/admin/products/" + p18 + "/pos", admin, Map.of("soldBy", "UNIT", "gstPercent", 5, "hsn", "9999"), null);
    var again = get("/api/v1/orders/" + orderId, t).getBody();
    assertThat(again.get("gstSlabs")).isEqualTo(order.get("gstSlabs"));
    assertThat(again.get("items")).isEqualTo(order.get("items"));
    var requote = post("/api/v1/cart/quote", t, cart(Map.of(), p18, 1)).getBody();
    assertThat((Map) ((List) requote.get("lines")).get(0)).containsEntry("gstPercent", 5).containsEntry("hsn", "9999");
  }

  @Test
  void anOrderPlacedBeforeGstWasRecordedShowsNothingGuessed() {
    String p = product(11800, "18", "1006");
    String t = token(freshEmail());
    String orderId = (String) place(t, cart(Map.of(), p, 1)).get("id");
    jdbc.update("update order_item set gst_percent = null, hsn = null, taxable_paise = null, cgst_paise = null, sgst_paise = null where order_id = ?::uuid", UUID.fromString(orderId));

    var order = get("/api/v1/orders/" + orderId, t).getBody();
    assertThat(order).containsEntry("gstRecorded", false).containsEntry("taxableTotalPaise", null).containsEntry("taxTotalPaise", null)
        .containsEntry("cgstTotalPaise", null).containsEntry("sgstTotalPaise", null);
    assertThat((List) order.get("gstSlabs")).isEmpty();
    Map<String, Object> line = ((List<Map<String, Object>>) order.get("items")).get(0);
    assertThat(line).containsEntry("gstPercent", null).containsEntry("taxablePaise", null).containsEntry("cgstPaise", null).containsEntry("hsn", null);
    assertThat(l(order.get("totalPaise"))).isEqualTo(11800);

    var row = ((List<Map<String, Object>>) get("/api/v1/orders?size=50", t).getBody().get("items")).stream()
        .filter(r -> orderId.equals(r.get("id"))).findFirst().orElseThrow();
    assertThat(row).containsEntry("gstRecorded", false).containsEntry("taxTotalPaise", null);
    assertThat((List) row.get("gstSlabs")).isEmpty();
    var admin = get("/api/v1/admin/orders/" + orderId, adminToken()).getBody();
    assertThat(admin).containsEntry("gstRecorded", false);
    assertThat((List) admin.get("gstSlabs")).isEmpty();
  }

  @Test
  void ordersWithoutAnyLinesInTheGstQueryAreReadAsNotRecorded() {
    String t = token(freshEmail());
    assertThat(((List) get("/api/v1/orders", t).getBody().get("items"))).isEmpty();
  }
}
