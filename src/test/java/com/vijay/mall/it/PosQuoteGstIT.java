package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The counter quote shows the GST of every line and slab, and the finished sale carries exactly the same figures. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PosQuoteGstIT extends IntegrationTestBase {
  private String product(long price, String gst) {
    String category = jdbc.queryForObject("select id::text from category limit 1", String.class);
    var res = post("/api/v1/admin/products", adminToken(), Map.of("categoryId", category, "nameEn", "Quote GST " + UUID.randomUUID(), "nameGu", "વસ્તુ",
        "mrpPaise", price + 1000, "pricePaise", price, "unit", "pack", "packSize", "1 kg", "qtyOnHand", 100));
    String id = (String) res.getBody().get("id");
    call(HttpMethod.PUT, "/api/v1/admin/products/" + id + "/pos", adminToken(), Map.of("soldBy", "UNIT", "gstPercent", new BigDecimal(gst), "hsn", "1006"), null);
    return id;
  }

  private static long l(Object o) { return ((Number) o).longValue(); }

  @Test
  void theQuoteAndTheFinishedSaleCarryTheSameGstLinesAndSlabs() {
    String admin = adminToken();
    String a = product(11800, "18");
    String b = product(10025, "0.25");
    String c = product(14000, "40");
    String d = product(5000, "0");
    String code = "PQG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    assertThat(post("/api/v1/admin/coupons", admin, Map.of("code", code, "kind", "FLAT", "value", 101)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String mobile = "98" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L));

    Map<String, Object> cart = new HashMap<>(Map.of("items", List.of(Map.of("productId", a, "qty", 3), Map.of("productId", b, "qty", 2),
        Map.of("productId", c, "qty", 1), Map.of("productId", d, "qty", 4)), "customerMobile", mobile, "couponCode", code));
    Map<String, Object> manual = Map.of("amountPaise", 333, "reason", "gst test");
    Map<String, Object> quoteBody = new HashMap<>(cart);
    quoteBody.put("manualDiscount", manual);

    var quote = post("/api/v1/pos/quote", admin, quoteBody).getBody();
    assertThat(l(quote.get("couponDiscountPaise"))).isPositive();
    List<Map<String, Object>> qLines = (List) quote.get("lines");
    assertThat(qLines).allSatisfy(x -> assertThat(l(x.get("taxablePaise")) + l(x.get("cgstPaise")) + l(x.get("sgstPaise"))).isEqualTo(l(x.get("netPaise"))));
    List<Map<String, Object>> slabs = (List) quote.get("gstSlabs");
    assertThat(slabs).extracting(s -> ((Number) s.get("gstPercent")).doubleValue()).containsExactly(0.0, 0.25, 18.0, 40.0);
    assertThat(slabs.stream().mapToLong(s -> l(s.get("taxablePaise"))).sum()).isEqualTo(l(quote.get("taxablePaise")));
    assertThat(slabs.stream().mapToLong(s -> l(s.get("cgstPaise"))).sum()).isEqualTo(l(quote.get("cgstPaise")));
    assertThat(slabs.stream().mapToLong(s -> l(s.get("sgstPaise"))).sum()).isEqualTo(l(quote.get("sgstPaise")));
    assertThat(slabs).allSatisfy(s -> assertThat(l(s.get("cgstPaise")) + l(s.get("sgstPaise"))).isEqualTo(l(s.get("taxPaise"))));

    Map<String, Object> body = new HashMap<>();
    body.put("cart", cart);
    body.put("manualDiscount", manual);
    body.put("payments", List.of(Map.of("mode", "CASH", "amountPaise", l(quote.get("totalPaise")))));
    var created = call(HttpMethod.POST, "/api/v1/pos/sales", admin, body, UUID.randomUUID().toString());
    assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
    Map sale = created.getBody();

    assertThat(l(sale.get("taxablePaise"))).isEqualTo(l(quote.get("taxablePaise")));
    assertThat(l(sale.get("cgstPaise"))).isEqualTo(l(quote.get("cgstPaise")));
    assertThat(l(sale.get("sgstPaise"))).isEqualTo(l(quote.get("sgstPaise")));
    List<Map<String, Object>> items = (List) sale.get("items");
    Map<String, Map<String, Object>> byProduct = new HashMap<>();
    items.forEach(i -> byProduct.put((String) i.get("productId"), i));
    for (Map<String, Object> ql : qLines) {
      Map<String, Object> si = byProduct.get(ql.get("productId"));
      assertThat(si.get("taxablePaise")).as("taxable").isEqualTo(ql.get("taxablePaise"));
      assertThat(si.get("cgstPaise")).isEqualTo(ql.get("cgstPaise"));
      assertThat(si.get("sgstPaise")).isEqualTo(ql.get("sgstPaise"));
      assertThat(si.get("netPaise")).isEqualTo(ql.get("netPaise"));
    }
    Map<Double, long[]> rebuilt = new TreeMap<>();
    for (Map<String, Object> si : items) {
      long[] sums = rebuilt.computeIfAbsent(((Number) si.get("gstPercent")).doubleValue(), k -> new long[3]);
      sums[0] += l(si.get("taxablePaise"));
      sums[1] += l(si.get("cgstPaise"));
      sums[2] += l(si.get("sgstPaise"));
    }
    assertThat(slabs).hasSameSizeAs(rebuilt.keySet());
    for (Map<String, Object> s : slabs) {
      long[] sums = rebuilt.get(((Number) s.get("gstPercent")).doubleValue());
      assertThat(new long[] {l(s.get("taxablePaise")), l(s.get("cgstPaise")), l(s.get("sgstPaise"))}).containsExactly(sums);
    }
  }
}
