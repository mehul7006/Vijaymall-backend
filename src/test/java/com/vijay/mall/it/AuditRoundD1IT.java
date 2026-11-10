package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Decimal unit costs, supplier e-mail and pincode, bank IFSC, lot origin and the all-fields entry error against real PostgreSQL. */
@SuppressWarnings({"unchecked", "rawtypes"})
class AuditRoundD1IT extends IntegrationTestBase {
  private static final String P = "/api/v1/admin/purchases";
  private static final String S = "/api/v1/admin/suppliers";
  private static final String M = "/api/v1/admin/money-accounts";

  private String admin() { return adminToken(); }

  private long n(Object o) { return ((Number) o).longValue(); }

  private Map fields(org.springframework.http.ResponseEntity<Map> res) { return (Map) ((Map) res.getBody().get("details")).get("fields"); }

  private String sharedProduct;

  private synchronized String product() {
    if (sharedProduct == null) {
      String category = jdbc.queryForObject("select id::text from category limit 1", String.class);
      var res = post("/api/v1/admin/products", admin(), Map.of("categoryId", category, "nameEn", "D1 Item " + UUID.randomUUID(), "nameGu", "વસ્તુ",
          "mrpPaise", 20000, "pricePaise", 18000, "unit", "g", "packSize", "1 kg", "qtyOnHand", 0));
      sharedProduct = (String) res.getBody().get("id");
    }
    return sharedProduct;
  }

  private org.springframework.http.ResponseEntity<Map> buy(String supplier, int qty, Object unitCost) {
    Map<String, Object> item = new HashMap<>(Map.of("productId", product(), "qty", qty));
    if (unitCost != null) item.put("unitCostPaise", unitCost);
    return call(HttpMethod.POST, P, admin(), Map.of("supplierName", supplier, "gstMode", "NONE", "items", List.of(item)), UUID.randomUUID().toString());
  }

  @Test
  void aWeighedPurchaseAcceptsAFractionalCostPerGramAndPrintsItWithoutNeedlessZeros() {
    String supplier = "D1 Weigh " + UUID.randomUUID();
    var res = buy(supplier, 12000, 5.5);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    Map line = ((List<Map>) res.getBody().get("items")).getFirst();
    assertThat(new BigDecimal(String.valueOf(line.get("unitCostPaise")))).isEqualByComparingTo("5.5");
    assertThat(line.get("unitCostPaise")).hasToString("5.5");
    assertThat(n(line.get("lineCostPaise"))).isEqualTo(66000);
    assertThat(n(line.get("taxablePaise"))).isEqualTo(66000);
    assertThat(n(res.getBody().get("totalCostPaise"))).isEqualTo(66000);
    assertThat(new BigDecimal(String.valueOf(line.get("landedUnitCostPaise")))).isEqualByComparingTo("5.5");

    Map whole = ((List<Map>) buy(supplier, 3, "55.0000").getBody().get("items")).getFirst();
    assertThat(whole.get("unitCostPaise")).isEqualTo(55);
    assertThat(n(whole.get("lineCostPaise"))).isEqualTo(165);
    Map hundreds = ((List<Map>) buy(supplier, 1, 100).getBody().get("items")).getFirst();
    assertThat(hundreds.get("unitCostPaise")).isEqualTo(100);
    Map rounded = ((List<Map>) buy(supplier, 3, 0.3333).getBody().get("items")).getFirst();
    assertThat(rounded.get("unitCostPaise")).hasToString("0.3333");
    assertThat(n(rounded.get("lineCostPaise"))).as("0.9999 rounds to a whole paisa").isEqualTo(1);

    var lookup = get(P + "/lookup?productId=" + product(), admin());
    assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(lookup.getBody().get("lastPurchaseCostPaise")).isEqualTo(0.3333);
    List<Map> history = (List<Map>) get("/api/v1/admin/products/" + product() + "/purchase-prices", admin()).getBody().get("_array");
    assertThat(history).extracting(r -> String.valueOf(r.get("unitCostPaise"))).contains("5.5", "55", "0.3333");
    var register = get("/api/v1/admin/reports/purchases/register?productId=" + product() + "&size=50", admin());
    assertThat(((List<Map>) register.getBody().get("items")).stream().map(r -> String.valueOf(r.get("unitCostPaise")))).contains("5.5", "55");
    assertThat(jdbc.queryForObject("select count(*) from purchase_item where unit_cost_paise = 5.5", Long.class)).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void aUnitCostOutsideTheRulesIsRefusedOnTheField() {
    String supplier = "D1 Bad " + UUID.randomUUID();
    var five = buy(supplier, 1, 1.23456);
    assertThat(five.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(five)).containsEntry("unitCostPaise", "at most 4 decimals");
    assertThat(fields(buy(supplier, 1, -0.5))).containsEntry("items[0].unitCostPaise", "must be at least 0");
    assertThat(fields(buy(supplier, 1, 100_000_000.5))).containsEntry("items[0].unitCostPaise", "must be at most 100000000");
    assertThat(buy(supplier, 1, 100_000_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(fields(buy(supplier, 1, null))).containsEntry("items[0].unitCostPaise", "is required");
    assertThat(buy(supplier, 1, "abc").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(buy(supplier, 1, 0).getStatusCode()).as("a free sample costs nothing").isEqualTo(HttpStatus.CREATED);
    Map<String, Object> item = Map.of("productId", product(), "qty", 1_000_000, "unitCostPaise", 1, "freeQty", 1);
    var free = call(HttpMethod.POST, P, admin(), Map.of("supplierName", supplier, "items", List.of(item)), UUID.randomUUID().toString());
    assertThat(free.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(free)).containsEntry("freeQty", "paid plus free must be at most 1000000");
  }

  @Test
  void aSupplierKeepsAnEmailAndPincodeAndCanBeFoundByEmail() {
    String name = "D1 Supplier " + UUID.randomUUID();
    String email = "d1-" + UUID.randomUUID().toString().substring(0, 8) + "@Agro.example.com";
    var created = post(S, admin(), Map.of("name", name, "email", email, "pincode", "380001"));
    assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).containsEntry("email", email).containsEntry("pincode", "380001");
    Object id = created.getBody().get("id");
    assertThat(get(S + "/" + id, admin()).getBody()).containsEntry("email", email).containsEntry("pincode", "380001");
    var found = get(S + "?q=" + email.substring(0, 11).toLowerCase(), admin()).getBody();
    assertThat((List<Map>) found.get("items")).anySatisfy(s -> assertThat(s).containsEntry("id", id).containsEntry("email", email));

    for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("name", name, "email", "nobody"), Map.of("name", name, "email", "a b@c.com"),
        Map.of("name", name, "pincode", "12345"), Map.of("name", name, "pincode", "12345a"))) {
      var res = call(HttpMethod.PUT, S + "/" + id, admin(), bad, null);
      assertThat(res.getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(fields(res).keySet()).containsAnyOf("email", "pincode");
    }
    assertThat(call(HttpMethod.POST, S, admin(), Map.of("name", name + "x", "email", "e".repeat(250) + "@x.co"), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, S, admin(), Map.of("name", name + "y", "email", "a".repeat(255)), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var cleared = call(HttpMethod.PUT, S + "/" + id, admin(), Map.of("name", name), null);
    assertThat(cleared.getBody()).containsEntry("email", "").containsEntry("pincode", "");
  }

  @Test
  void onlyABankAccountHasAnIfscAndItIsUpperCased() {
    String admin = admin();
    String name = "D1 Bank " + UUID.randomUUID().toString().substring(0, 8);
    var bank = post(M, admin, Map.of("name", name, "kind", "BANK", "ifsc", " hdfc0001234 "));
    assertThat(bank.getStatusCode()).as(String.valueOf(bank.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(bank.getBody()).containsEntry("ifsc", "HDFC0001234");
    var list = get(M + "?kind=BANK&q=hdfc0001234&size=50", admin).getBody();
    assertThat((List<Map>) list.get("items")).anySatisfy(a -> assertThat(a).containsEntry("ifsc", "HDFC0001234"));
    var upi = post(M, admin, Map.of("name", name + " upi", "kind", "UPI", "ifsc", "HDFC0001234"));
    assertThat(upi.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(upi)).containsEntry("ifsc", "only a BANK account has an IFSC code");
    var bad = post(M, admin, Map.of("name", name + " bad", "kind", "BANK", "ifsc", "HDFC1001234"));
    assertThat(fields(bad)).containsKey("ifsc");
    var none = post(M, admin, Map.of("name", name + " none", "kind", "BANK"));
    assertThat(none.getBody().get("ifsc")).isNull();
    var changed = call(HttpMethod.PUT, M + "/" + bank.getBody().get("id"), admin, Map.of("name", name, "kind", "BANK", "ifsc", ""), null);
    assertThat(changed.getBody().get("ifsc")).isNull();
  }

  @Test
  void aBadEntryReportsEveryWrongFieldTogether() {
    String admin = admin();
    Object id = get(M + "?kind=CASH", admin).getBody() instanceof Map m ? ((List<Map>) m.get("items")).getFirst().get("id") : null;
    var both = post(M + "/" + id + "/entries", admin, Map.of("direction", "SIDE", "amountPaise", 0));
    assertThat(both.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(both)).containsKeys("direction", "amountPaise").hasSize(2);
    var three = post(M + "/" + id + "/entries", admin, Map.of("direction", "SIDE", "amountPaise", 0, "note", "n".repeat(201)));
    assertThat(fields(three)).containsKeys("direction", "amountPaise", "note");
    var one = post(M + "/" + id + "/entries", admin, Map.of("direction", "IN", "amountPaise", 0));
    assertThat(fields(one)).containsOnlyKeys("amountPaise");
  }

  @Test
  void aLotNamesThePurchaseThatDeliveredItAndAnOpeningLotHasNone() {
    String supplier = "D1 Lots " + UUID.randomUUID();
    var res = buy(supplier, 10, 7);
    String purchaseId = (String) res.getBody().get("id");
    String invoice = (String) res.getBody().get("invoiceNo");
    String product = product();
    List<Map> lots = (List<Map>) get("/api/v1/admin/variants/" + product + "/lots?size=50", admin()).getBody().get("items");
    assertThat(lots).anySatisfy(l -> assertThat(l).containsEntry("purchaseId", purchaseId).containsEntry("invoiceNo", invoice).containsEntry("supplierName", supplier));
    jdbc.update("insert into stock_lot (id, product_id, received_at, qty_received, qty_remaining, unit_cost, cost_known, source, created_at) "
        + "values (?::uuid, ?::uuid, now(), 1, 0, 0, false, 'OPENING', now())", UUID.randomUUID().toString(), product);
    List<Map> again = (List<Map>) get("/api/v1/admin/variants/" + product + "/lots?size=50", admin()).getBody().get("items");
    assertThat(again).anySatisfy(l -> assertThat(l).containsEntry("source", "OPENING").containsEntry("purchaseId", null).containsEntry("invoiceNo", null));
  }
}
