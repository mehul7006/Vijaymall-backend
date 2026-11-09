package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.pos.domain.BarcodeParser;
import com.vijay.mall.support.IntegrationTestBase;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Stock lots against real PostgreSQL: receipt, FIFO sales and costs, lot-aware prices, online cap, returns, stock take, offline sync and reports. */
@SuppressWarnings({"unchecked", "rawtypes"})
class StockLotsIT extends IntegrationTestBase {
  private String admin() { return adminToken(); }

  /** Switches the products made here off afterwards so they do not crowd the first page of the shop list other tests read. */
  @org.junit.jupiter.api.AfterEach
  void tidy() { jdbc.update("update product set active = false where name_en like 'Lot Item %'"); }

  private long n(Object o) { return ((Number) o).longValue(); }

  /** A fresh product with no stock, lot priced when asked, with a barcode. */
  private String product(boolean lotPriced, boolean trackExpiry) {
    String category = jdbc.queryForObject("select id::text from category limit 1", String.class);
    var res = post("/api/v1/admin/products", admin(), Map.of("categoryId", category, "nameEn", "Lot Item " + UUID.randomUUID(), "nameGu", "વસ્તુ",
        "mrpPaise", 20000, "pricePaise", 18000, "unit", "pack", "packSize", "1 kg", "qtyOnHand", 0));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) res.getBody().get("id");
    jdbc.update("insert into product_pos (product_id, gst_percent, hsn, updated_at) values (?::uuid, 0, '', now())", id);
    if (lotPriced || trackExpiry) {
      var r = call(HttpMethod.PUT, "/api/v1/admin/variants/" + id + "/lot-settings", admin(), Map.of("lotPriced", lotPriced, "trackExpiry", trackExpiry), null);
      assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    return id;
  }

  private String barcode(String id) {
    String body = String.format("89%010d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L);
    String code = body + BarcodeParser.checkDigit(body);
    jdbc.update("insert into product_barcode (code, product_id, created_at) values (?, ?::uuid, now())", code, id);
    return code;
  }

  private Map receive(String product, int qty, long cost, Long price, Long mrp, String expiry) {
    Map<String, Object> item = new HashMap<>(Map.of("productId", product, "qty", qty, "unitCostPaise", cost));
    if (price != null) item.put("sellingPricePaise", price);
    if (mrp != null) item.put("mrpPaise", mrp);
    if (expiry != null) item.put("expiryDate", expiry);
    var res = call(HttpMethod.POST, "/api/v1/admin/purchases", admin(), Map.of("supplierName", "Lot Supplier", "items", List.of(item)), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private List<Map> lots(String product) {
    return (List<Map>) get("/api/v1/admin/variants/" + product + "/lots?size=50", admin()).getBody().get("items");
  }

  private int remaining(String product) {
    return jdbc.queryForObject("select coalesce(sum(qty_remaining), 0) from stock_lot where product_id = ?::uuid", Integer.class, product);
  }

  private int onHand(String product) { return jdbc.queryForObject("select qty_on_hand from inventory where product_id = ?::uuid", Integer.class, product); }

  private long price(String product) { return jdbc.queryForObject("select price_paise from product where id = ?::uuid", Long.class, product); }

  private void invariant(String product) { assertThat(remaining(product)).as("lots add up to stock").isEqualTo(onHand(product)); }

  private Map sale(String product, int qty) {
    String cashier = token("cashier@vijaysupermall.local");
    Map cart = Map.of("items", List.of(Map.of("productId", product, "qty", qty)));
    var quote = post("/api/v1/pos/quote", cashier, cart);
    assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.OK);
    long total = n(quote.getBody().get("totalPaise"));
    var res = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", cart, "payments", List.of(Map.of("mode", "CASH", "amountPaise", total))),
        UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  @Test
  void aPurchaseOpensLotsWithLandedCostAndTheShopPriceFollowsTheCurrentLot() {
    String p = product(true, false);
    receive(p, 3, 10000, 15000L, 16000L, null);
    receive(p, 10, 12000, 17000L, 18000L, null);
    assertThat(lots(p)).hasSize(2);
    assertThat(price(p)).as("the oldest lot sets the price").isEqualTo(15000);
    assertThat(onHand(p)).isEqualTo(13);
    invariant(p);
    // other tests set stock with raw SQL, so only this variant is checked
    assertThat(((List<Map>) get("/api/v1/admin/stock/lot-check", admin()).getBody().get("drifts"))).noneMatch(d -> p.equals(d.get("productId")));
    var shop = get("/api/v1/products/" + p, token(freshEmail())).getBody();
    assertThat(n(shop.get("pricePaise"))).isEqualTo(15000);
  }

  @Test
  void aCounterSaleAcrossTwoLotsPricesEachLotCostsItAndRollsThePriceOver() {
    String p = product(true, false);
    String code = barcode(p);
    receive(p, 3, 10000, 15000L, 16000L, null);
    receive(p, 10, 12000, 17000L, 18000L, null);

    var scan = get("/api/v1/pos/barcode/" + code, token("cashier@vijaysupermall.local")).getBody();
    Map lot = (Map) scan.get("lot");
    assertThat(n(lot.get("qtyLeftInLot"))).isEqualTo(3);
    assertThat((Map) lot.get("nextPriceChange")).containsEntry("afterQty", 3).containsEntry("newPricePaise", 17000).containsEntry("newMrpPaise", 18000);
    assertThat(n(((Map) scan.get("product")).get("pricePaise"))).isEqualTo(15000);

    Map bill = sale(p, 5);
    List<Map> items = (List<Map>) bill.get("items");
    assertThat(items).extracting(i -> n(i.get("qty")), i -> n(i.get("unitPricePaise"))).containsExactly(
        org.assertj.core.groups.Tuple.tuple(3L, 15000L), org.assertj.core.groups.Tuple.tuple(2L, 17000L));
    assertThat(items.getFirst()).doesNotContainKey("costPaise");
    assertThat(jdbc.queryForList("select cost_paise from pos_sale_item where sale_id = ?::uuid order by qty desc", Long.class, bill.get("id")))
        .containsExactly(30000L, 24000L);
    assertThat(price(p)).as("the price rolled to the next lot").isEqualTo(17000);
    assertThat(remaining(p)).isEqualTo(8);
    invariant(p);

    var margin = (List<Map>) get("/api/v1/admin/reports/margin?groupBy=product&size=50", admin()).getBody().get("items");
    Map row = margin.stream().filter(m -> p.equals(m.get("key"))).findFirst().orElseThrow();
    assertThat(n(row.get("revenuePaise"))).isEqualTo(3 * 15000 + 2 * 17000);
    assertThat(n(row.get("costPaise"))).isEqualTo(54000);
    assertThat(n(row.get("marginPaise"))).isEqualTo(79000 - 54000);

    // a return puts the unit back into the lot it came from at its cost
    String lineId = (String) items.get(1).get("id");
    var ret = call(HttpMethod.POST, "/api/v1/pos/sales/" + bill.get("id") + "/returns", token("cashier@vijaysupermall.local"),
        Map.of("items", List.of(Map.of("saleItemId", lineId, "qty", 1)), "reason", "changed mind", "refunds", List.of(Map.of("mode", "CASH", "amountPaise", 17000))),
        UUID.randomUUID().toString());
    assertThat(ret.getStatusCode()).as(String.valueOf(ret.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(remaining(p)).isEqualTo(9);
    assertThat(jdbc.queryForObject("select sum(returned_cost_paise) from stock_allocation where ref_id = ?::uuid", Long.class, lineId)).isEqualTo(12000L);
    invariant(p);
  }

  @Test
  void aPosQuoteSplitsTheLinePerLotPrice() {
    String p = product(true, false);
    receive(p, 2, 100, 500L, 600L, null);
    receive(p, 5, 100, 700L, 800L, null);
    var quote = post("/api/v1/pos/quote", token("cashier@vijaysupermall.local"), Map.of("items", List.of(Map.of("productId", p, "qty", 4))));
    List<Map> lines = (List<Map>) quote.getBody().get("lines");
    assertThat(lines).extracting(l -> n(l.get("qty")), l -> n(l.get("unitPricePaise"))).containsExactly(
        org.assertj.core.groups.Tuple.tuple(2L, 500L), org.assertj.core.groups.Tuple.tuple(2L, 700L));
  }

  @Test
  void onlineOrdersNeverMixPricesAndCancelPutsUnitsBackInTheirLot() {
    String p = product(true, false);
    receive(p, 3, 10000, 15000L, 16000L, null);
    receive(p, 5, 12000, 17000L, 18000L, null);
    String cust = token(freshEmail());
    var items = List.of(Map.of("productId", p, "qty", 4));
    var refused = post("/api/v1/cart/quote", cust, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP"));
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(refused.getBody()).containsEntry("code", "QTY_EXCEEDS_LOT_PRICE");
    Map details = (Map) (refused.getBody().containsKey("details") ? refused.getBody().get("details") : refused.getBody());
    assertThat(n(details.get("maxQty"))).isEqualTo(3);
    assertThat(String.valueOf(refused.getBody().get("message"))).contains("Only 3 available at this price");

    var ok = List.of(Map.of("productId", p, "qty", 3));
    var req = Map.of("items", ok, "coinMode", "NONE", "fulfilment", "PICKUP");
    long total = n(post("/api/v1/cart/quote", cust, req).getBody().get("totalPaise"));
    assertThat(total).isEqualTo(45000);
    var placed = call(HttpMethod.POST, "/api/v1/orders", cust, Map.of("items", ok, "coinMode", "NONE", "fulfilment", "PICKUP", "paymentMethod", "COD",
        "expectedTotalPaise", total), UUID.randomUUID().toString());
    String id = (String) placed.getBody().get("id");
    assertThat(remaining(p)).as("placing takes nothing").isEqualTo(8);
    post("/api/v1/admin/orders/" + id + "/status", admin(), Map.of("status", "CONFIRMED"));
    assertThat(remaining(p)).isEqualTo(5);
    assertThat(price(p)).isEqualTo(17000);
    assertThat(jdbc.queryForObject("select cost_paise from order_item where order_id = ?::uuid", Long.class, id)).isEqualTo(30000L);
    invariant(p);
    assertThat(post("/api/v1/orders/" + id + "/cancel", cust, null).getBody().get("status")).isEqualTo("CANCELLED");
    assertThat(remaining(p)).isEqualTo(8);
    assertThat(price(p)).as("the old lot sells again at its price").isEqualTo(15000);
    assertThat(jdbc.queryForObject("select qty_remaining from stock_lot where product_id = ?::uuid order by received_at limit 1", Integer.class, p)).isEqualTo(3);
    invariant(p);
  }

  @Test
  void stockTakeAdjustmentsAndPurchaseReturnsKeepLotsInStep() {
    String p = product(false, false);
    Map purchase = receive(p, 10, 5000, null, null, null);
    receive(p, 10, 6000, null, null, null);
    var take = call(HttpMethod.POST, "/api/v1/admin/stock-takes", admin(), Map.of("items", List.of(Map.of("productId", p, "countedQty", 17))), UUID.randomUUID().toString());
    assertThat(take.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(remaining(p)).isEqualTo(17);
    assertThat(jdbc.queryForObject("select qty_remaining from stock_lot where product_id = ?::uuid order by received_at limit 1", Integer.class, p)).as("missing units come off the oldest lot").isEqualTo(7);
    call(HttpMethod.POST, "/api/v1/admin/products/" + p + "/stock", admin(), Map.of("delta", 4, "reason", "RESTOCK"), UUID.randomUUID().toString());
    assertThat(jdbc.queryForObject("select count(*) from stock_lot where product_id = ?::uuid and source = 'ADJUSTMENT'", Long.class, p)).isEqualTo(1L);
    assertThat(remaining(p)).isEqualTo(21);

    String lineId = (String) ((List<Map>) purchase.get("items")).getFirst().get("id");
    var ret = call(HttpMethod.POST, "/api/v1/admin/purchases/" + purchase.get("id") + "/returns", admin(),
        Map.of("reason", "damaged", "items", List.of(Map.of("purchaseItemId", lineId, "qty", 5))), UUID.randomUUID().toString());
    assertThat(ret.getStatusCode()).as(String.valueOf(ret.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(jdbc.queryForObject("select qty_remaining from stock_lot where purchase_item_id = ?::uuid", Integer.class, lineId)).isEqualTo(2);
    invariant(p);
  }

  @Test
  void anOfflineBillTakesLotsAndAShortfallStillBalances() {
    String p = product(false, false);
    receive(p, 2, 1000, null, null, null);
    String cashier = token("cashier@vijaysupermall.local");
    Map cart = Map.of("items", List.of(Map.of("productId", p, "qty", 3)));
    long total = n(post("/api/v1/pos/quote", cashier, cart).getBody().get("totalPaise"));
    Map bill = new HashMap<>(Map.of("idempotencyKey", UUID.randomUUID().toString(), "cart", cart, "payments", List.of(Map.of("mode", "CASH", "amountPaise", total))));
    var res = post("/api/v1/pos/sync/sales", cashier, Map.of("sales", List.of(bill)));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(remaining(p)).isZero();
    assertThat(onHand(p)).isZero();
    invariant(p);
  }

  @Test
  void expiryDatedLotsSellEarliestExpiryFirstAndShowUpInTheExpiryListAndWriteOff() {
    String p = product(false, true);
    receive(p, 5, 100, null, null, LocalDate.now().plusDays(300).toString());
    receive(p, 5, 100, null, null, LocalDate.now().plusDays(10).toString());
    sale(p, 2);
    assertThat(jdbc.queryForObject("select qty_remaining from stock_lot where product_id = ?::uuid and expiry_date < now() + interval '100 days'", Integer.class, p)).isEqualTo(3);
    var soon = (List<Map>) get("/api/v1/admin/stock/expiring?days=30", admin()).getBody().get("lots");
    Map lot = soon.stream().filter(l -> p.equals(l.get("productId"))).findFirst().orElseThrow();
    assertThat(get("/api/v1/admin/stock/expiring?days=999", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var off = post("/api/v1/admin/stock/lots/" + lot.get("id") + "/write-off", admin(), Map.of("reason", "expired"));
    assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(n(off.getBody().get("unitsWrittenOff"))).isEqualTo(3);
    assertThat(post("/api/v1/admin/stock/lots/" + lot.get("id") + "/write-off", admin(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(post("/api/v1/admin/stock/lots/" + UUID.randomUUID() + "/write-off", admin(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(onHand(p)).isEqualTo(5);
    invariant(p);
  }

  @Test
  void valuationMarginAndSettingsEndpointsAnswerAndAreAdminOnly() {
    String p = product(false, false);
    receive(p, 4, 2500, null, null, null);
    var byProduct = get("/api/v1/admin/stock/valuation?groupBy=product&size=50", admin()).getBody();
    assertThat(n(((Map) byProduct.get("total")).get("valuePaise"))).isGreaterThanOrEqualTo(10000);
    assertThat(get("/api/v1/admin/stock/valuation?groupBy=category", admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/admin/stock/valuation?groupBy=bad", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    for (String g : List.of("category", "day")) {
      assertThat(get("/api/v1/admin/reports/margin?groupBy=" + g, admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    assertThat(get("/api/v1/admin/reports/margin?groupBy=bad", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/reports/margin?from=2026-02-01&to=2026-01-01", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/reports/margin?from=2020-01-01&to=2026-01-01", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/stock/lot-check", token("cashier@vijaysupermall.local")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/variants/" + UUID.randomUUID() + "/lot-settings", admin(), Map.of("lotPriced", true, "trackExpiry", false), null)
        .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/variants/" + UUID.randomUUID() + "/lot-settings", admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    String switched = product(true, false);
    var saved = get("/api/v1/admin/variants/" + switched + "/lot-settings", admin());
    assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(saved.getBody()).containsEntry("lotPriced", true).containsEntry("trackExpiry", false).containsEntry("variantId", switched);
    var bad = call(HttpMethod.PUT, "/api/v1/admin/config", admin(), Map.of("changes", List.of(Map.of("key", "inventory.lotCrossPolicy", "value", "SPLIT"))), null);
    assertThat(bad.getStatusCode().is4xxClientError()).isTrue();
  }
}
