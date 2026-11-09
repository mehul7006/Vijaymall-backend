package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.pos.domain.BarcodeParser;
import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The shop counter against real PostgreSQL: scan, bill, split payments, coins, returns, holds, cash-up, access rules. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PosFlowIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  @BeforeEach
  void stock() {
    restock("Basmati Rice", 100);
  }

  private static String ean(String body12) { return body12 + BarcodeParser.checkDigit(body12); }

  private String cashier() { return token(CASHIER); }

  /** The 1 kg pack. Another test adds a 5 kg variant with the same name, so the name alone is not enough. */
  private Object riceId() {
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", cashier()).getBody().get("items");
    return items.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst().orElseThrow().get("id");
  }

  private Object sugarId(String cashier) {
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/pos/products?q=Sugar", cashier).getBody().get("items");
    return items.get(0).get("productId");
  }

  private int stockOf(String name) {
    return jdbc.queryForObject("select sum(i.qty_on_hand) from inventory i join product p on p.id = i.product_id where p.name_en = ? "
        + "and (p.pack_size = '1 kg' or p.pack_size = '' or p.pack_size is null or p.name_en <> 'Basmati Rice')", Integer.class, name);
  }

  private Map sale(String token, Map body, String key) {
    var res = call(HttpMethod.POST, "/api/v1/pos/sales", token, body, key);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  // ---------- access rules ----------
  @Test void onlyStaffReachTheCounter_andCashiersStayOutOfAdmin() {
    String customer = token(freshEmail());
    assertThat(get("/api/v1/pos/shop", customer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/pos/shop", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    String cashier = cashier();
    assertThat(get("/api/v1/pos/shop", cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/admin/dashboard", cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/pos/shop", adminToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  // ---------- scan ----------
  @Test void scanFindsPackBarcodeAndWeighedLabel() {
    String cashier = cashier();
    var pack = get("/api/v1/pos/barcode/" + ean("890100000001"), cashier);
    assertThat(pack.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Map) pack.getBody().get("product")).get("name")).isEqualTo("Basmati Rice");
    assertThat(pack.getBody().get("qty")).isEqualTo(1);
    assertThat(pack.getBody().get("linePaise")).isEqualTo(12500);

    var loose = get("/api/v1/pos/barcode/" + ean("200010101500"), cashier); // item code 101, 1500 g
    assertThat(loose.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loose.getBody().get("qty")).isEqualTo(1500);
    assertThat(loose.getBody().get("linePaise")).isEqualTo(6750); // Rs 45 per kg

    assertThat(get("/api/v1/pos/barcode/0000000000", cashier).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test void looseItemsAreNeverShownOrSoldOnline() {
    String customer = token(freshEmail());
    List<Map<String, Object>> online = (List<Map<String, Object>>) get("/api/v1/products?size=50", customer).getBody().get("items");
    assertThat(online).noneMatch(p -> "Loose Sugar".equals(p.get("nameEn")));
    Object sugar = sugarId(cashier());
    assertThat(get("/api/v1/products/" + sugar, customer).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var quote = post("/api/v1/cart/quote", customer, Map.of("items", List.of(Map.of("productId", sugar, "qty", 1)), "coinMode", "NONE"));
    assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ---------- billing ----------
  @Test void splitPaymentBill_reservesStock_isIdempotent_andPrintsReceipt() {
    String cashier = cashier();
    Object rice = riceId();
    int before = stockOf("Basmati Rice");
    Map cart = Map.of("items", List.of(Map.of("productId", rice, "qty", 2)));

    var quote = post("/api/v1/pos/quote", cashier, cart).getBody();
    assertThat(quote.get("totalPaise")).isEqualTo(25000);
    assertThat(quote.get("taxablePaise")).isEqualTo(23810); // 5% GST inside the price
    assertThat(quote.get("cgstPaise")).isEqualTo(595);
    assertThat(quote.get("sgstPaise")).isEqualTo(595);

    Map body = Map.of("cart", cart, "cashTenderedPaise", 12000,
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", 10000), Map.of("mode", "UPI", "amountPaise", 15000, "reference", "UTR1")));
    String key = UUID.randomUUID().toString();
    Map first = sale(cashier, body, key);
    assertThat((String) first.get("invoiceNo")).matches("VSM/\\d{4}-\\d{2}/\\d{6}");
    assertThat(first.get("changePaise")).isEqualTo(2000);
    assertThat(first.get("totalPaise")).isEqualTo(25000);
    assertThat(stockOf("Basmati Rice")).isEqualTo(before - 2);

    Map again = sale(cashier, body, key);
    assertThat(again.get("id")).isEqualTo(first.get("id"));
    assertThat(stockOf("Basmati Rice")).isEqualTo(before - 2); // not charged twice

    var receipt = get("/api/v1/pos/sales/" + first.get("id") + "/receipt?width=32", cashier);
    assertThat((String) receipt.getBody().get("text")).contains("TAX INVOICE", (String) first.get("invoiceNo"), "Basmati Rice");
    assertThat(get("/api/v1/pos/sales/" + first.get("id") + "/receipt?width=40", cashier).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/pos/sales?invoiceNo=" + ((String) first.get("invoiceNo")).replace("/", "%2F"), cashier).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test void paymentsThatDoNotAddUp_areRejected_andStockIsUntouched() {
    String cashier = cashier();
    int before = stockOf("Basmati Rice");
    var res = call(HttpMethod.POST, "/api/v1/pos/sales", cashier,
        Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1))),
            "payments", List.of(Map.of("mode", "CASH", "amountPaise", 100))), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(stockOf("Basmati Rice")).isEqualTo(before);
    var noKey = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", Map.of("items", List.of())), null);
    assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test void weighedItemIsBilledByGrams() {
    String cashier = cashier();
    int before = stockOf("Loose Sugar");
    Map body = Map.of("cart", Map.of("items", List.of(Map.of("productId", sugarId(cashier), "qty", 1500))),
        "payments", List.of(Map.of("mode", "CARD", "amountPaise", 6750, "reference", "APPR1")));
    Map s = sale(cashier, body, UUID.randomUUID().toString());
    assertThat(s.get("totalPaise")).isEqualTo(6750);
    assertThat(stockOf("Loose Sugar")).isEqualTo(before - 1500);
  }

  @Test void couponAndCoins_needACustomer_thenEarnAndAreTakenBackOnReturn() {
    String cashier = cashier();
    Object rice = riceId();
    String mobile = "98" + (10000000 + (int) (Math.random() * 89999999));
    Map items = Map.of("items", List.of(Map.of("productId", rice, "qty", 10)));

    var noMobile = post("/api/v1/pos/quote", cashier, Map.of("items", List.of(Map.of("productId", rice, "qty", 10)), "couponCode", "FESTIVE50"));
    assertThat(noMobile.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    Map cart = Map.of("items", List.of(Map.of("productId", rice, "qty", 10)), "customerMobile", mobile, "couponCode", "FESTIVE50");
    var quote = post("/api/v1/pos/quote", cashier, cart).getBody();
    assertThat(quote.get("couponDiscountPaise")).isEqualTo(5000);
    assertThat(quote.get("totalPaise")).isEqualTo(120000);
    assertThat(quote.get("coinsToEarn")).isEqualTo(12);

    Map s = sale(cashier, Map.of("cart", cart, "payments", List.of(Map.of("mode", "UPI", "amountPaise", 120000))), UUID.randomUUID().toString());
    assertThat(s.get("coinsEarned")).isEqualTo(12);
    assertThat(jdbc.queryForObject("select wb.coins from wallet_balance wb join users u on u.id = wb.user_id where u.mobile = ?",
        Integer.class, mobile)).isEqualTo(12);
    assertThat(jdbc.queryForObject("select source from users where mobile = ? and source = 'POS'", String.class, mobile)).isEqualTo("POS");

    // return everything: money, stock and the earned coins all go back
    List<Map<String, Object>> lines = (List<Map<String, Object>>) s.get("items");
    int stockBefore = stockOf("Basmati Rice");
    var ret = call(HttpMethod.POST, "/api/v1/pos/sales/" + s.get("id") + "/returns", cashier,
        Map.of("items", List.of(Map.of("saleItemId", lines.get(0).get("id"), "qty", 10)), "reason", "changed mind"),
        UUID.randomUUID().toString());
    assertThat(ret.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(ret.getBody().get("refundPaise")).isEqualTo(120000);
    assertThat(ret.getBody().get("coinsClawedBack")).isEqualTo(12);
    assertThat((String) ret.getBody().get("creditNoteNo")).matches("VSM-CN/\\d{4}-\\d{2}/\\d{6}");
    assertThat(stockOf("Basmati Rice")).isEqualTo(stockBefore + 10);
    assertThat(jdbc.queryForObject("select wb.coins from wallet_balance wb join users u on u.id = wb.user_id where u.mobile = ?",
        Integer.class, mobile)).isZero();

    var tooMany = call(HttpMethod.POST, "/api/v1/pos/sales/" + s.get("id") + "/returns", cashier,
        Map.of("items", List.of(Map.of("saleItemId", lines.get(0).get("id"), "qty", 1))), UUID.randomUUID().toString());
    assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(tooMany.getBody().get("code")).isEqualTo("RETURN_EXCEEDS_SOLD");
    assertThat(getList("/api/v1/pos/sales/" + s.get("id") + "/returns", cashier)).hasSize(1);
  }

  @Test void partialReturnRefundsItsShare_andRepeatedKeyIsHarmless() {
    String cashier = cashier();
    Map s = sale(cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 4))),
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", 50000))), UUID.randomUUID().toString());
    List<Map<String, Object>> lines = (List<Map<String, Object>>) s.get("items");
    Map body = Map.of("items", List.of(Map.of("saleItemId", lines.get(0).get("id"), "qty", 1)),
        "refunds", List.of(Map.of("mode", "CASH", "amountPaise", 12500)));
    String key = UUID.randomUUID().toString();
    var first = call(HttpMethod.POST, "/api/v1/pos/sales/" + s.get("id") + "/returns", cashier, body, key);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.getBody().get("refundPaise")).isEqualTo(12500);
    var again = call(HttpMethod.POST, "/api/v1/pos/sales/" + s.get("id") + "/returns", cashier, body, key);
    assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    assertThat(getList("/api/v1/pos/sales/" + s.get("id") + "/returns", cashier)).hasSize(1);
    var wrong = call(HttpMethod.POST, "/api/v1/pos/sales/" + s.get("id") + "/returns", cashier,
        Map.of("items", List.of(Map.of("saleItemId", lines.get(0).get("id"), "qty", 1)),
            "refunds", List.of(Map.of("mode", "CASH", "amountPaise", 1))), UUID.randomUUID().toString());
    assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ---------- holds, UPI link, cash-up ----------
  @Test void heldBasketsCanBeListedRecalledAndDiscarded() {
    String cashier = cashier();
    var created = post("/api/v1/pos/holds", cashier,
        Map.of("label", "Mrs Shah", "cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 3)))));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Object id = created.getBody().get("id");
    assertThat(getList("/api/v1/pos/holds", cashier)).anyMatch(h -> id.equals(h.get("id")));
    var one = get("/api/v1/pos/holds/" + id, cashier);
    assertThat(((Map) one.getBody().get("cart")).get("items")).isNotNull();
    assertThat(call(HttpMethod.DELETE, "/api/v1/pos/holds/" + id, cashier, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(get("/api/v1/pos/holds/" + id, cashier).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test void theHundredAndFirstHeldBasketIsRefusedSoNothingDropsOutOfTheList() {
    String cashier = cashier();
    List<Object> ids = new java.util.ArrayList<>();
    try {
      int room = 100 - getList("/api/v1/pos/holds", cashier).size();
      for (int i = 0; i < room; i++) {
        var made = post("/api/v1/pos/holds", cashier,
            Map.of("label", "bulk " + i, "cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1)))));
        assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ids.add(made.getBody().get("id"));
      }
      var over = post("/api/v1/pos/holds", cashier,
          Map.of("label", "one too many", "cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1)))));
      assertThat(over.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(over.getBody().get("code")).isEqualTo("HOLD_LIMIT");
      assertThat(over.getBody().get("message")).isEqualTo("Too many parked baskets: resume or discard some first");
      assertThat(((Map) over.getBody().get("details")).get("max")).isEqualTo(100);
      assertThat(getList("/api/v1/pos/holds", cashier)).hasSize(100);
      if (!ids.isEmpty()) {
        call(HttpMethod.DELETE, "/api/v1/pos/holds/" + ids.remove(0), cashier, null, null);
        assertThat(post("/api/v1/pos/holds", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1))))).getStatusCode())
            .as("room again after one is discarded").isEqualTo(HttpStatus.CREATED);
        ids.addAll(getList("/api/v1/pos/holds", cashier).stream().map(h -> h.get("id")).filter(id -> !ids.contains(id)).toList());
      }
    } finally {
      ids.forEach(id -> call(HttpMethod.DELETE, "/api/v1/pos/holds/" + id, cashier, null, null));
    }
  }

  @Test void aHeldBasketKeepsToItsBounds() {
    String cashier = cashier();
    Object rice = riceId();
    for (int qty : new int[] {0, -1, 10000, 99999}) {
      var res = post("/api/v1/pos/holds", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", rice, "qty", qty)))));
      assertThat(res.getStatusCode()).as("qty " + qty).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(((Map) ((Map) res.getBody().get("details")).get("fields")).get("cart.items[0].qty")).isEqualTo("must be 1 to 9999");
    }
    var longLabel = post("/api/v1/pos/holds", cashier, Map.of("label", "x".repeat(61), "cart", Map.of("items", List.of(Map.of("productId", rice, "qty", 1)))));
    assertThat(longLabel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(((Map) ((Map) longLabel.getBody().get("details")).get("fields")).get("label")).isNotNull();
    var many = new java.util.ArrayList<Map<String, Object>>();
    for (int i = 0; i < 51; i++) many.add(Map.of("productId", rice, "qty", 1));
    var tooMany = post("/api/v1/pos/holds", cashier, Map.of("cart", Map.of("items", many)));
    assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(((Map) ((Map) tooMany.getBody().get("details")).get("fields")).get("items")).isEqualTo("must have at most 50 lines");
    var ok = post("/api/v1/pos/holds", cashier, Map.of("label", "x".repeat(60), "cart", Map.of("items", List.of(Map.of("productId", rice, "qty", 9999)))));
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    call(HttpMethod.DELETE, "/api/v1/pos/holds/" + ok.getBody().get("id"), cashier, null, null);
  }

  @Test void upiLinkNeedsTheShopUpiId() {
    String admin = adminToken();
    assertThat(get("/api/v1/pos/upi-link?amountPaise=100", cashier()).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    var saved = call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin,
        Map.of("shopName", "Vijay Super Mall", "address", "Market Road", "phone", "9876543210", "gstin", "24ABCDE1234F1Z5",
            "stateCode", "24", "upiId", "vijay@upi", "invoicePrefix", "VSM", "receiptFooter", "Thanks", "weighedPrefix", "2",
            "weighedMode", "WEIGHT_GRAMS"), null);
    assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
    var link = get("/api/v1/pos/upi-link?amountPaise=12345&note=bill", cashier());
    assertThat((String) link.getBody().get("link")).startsWith("upi://pay?pa=vijay%40upi").contains("am=123.45");
    var bad = call(HttpMethod.PUT, "/api/v1/admin/shop-settings", admin,
        Map.of("shopName", "V", "gstin", "BAD", "stateCode", "24", "invoicePrefix", "VSM", "weighedPrefix", "2"), null);
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test void cashUpAddsUpSalesAndRefunds() {
    String cashier = cashier();
    sale(cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1))),
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", 12500))), UUID.randomUUID().toString());
    var day = get("/api/v1/pos/reports/day", cashier).getBody();
    long sales = ((Number) day.get("salesPaise")).longValue();
    long refunds = ((Number) day.get("refundsPaise")).longValue();
    assertThat(((Number) day.get("bills")).longValue()).isGreaterThanOrEqualTo(1);
    assertThat(((Number) day.get("netPaise")).longValue()).isEqualTo(sales - refunds);
    Map paid = (Map) day.get("paidByMode");
    Map back = (Map) day.get("refundedByMode");
    assertThat(((Number) day.get("cashExpectedPaise")).longValue())
        .isEqualTo(((Number) paid.get("CASH")).longValue() - ((Number) back.get("CASH")).longValue()
            + ((Number) ((Map) day.get("creditRecoveredByMode")).get("CASH")).longValue()); // cash repayments go in the drawer too
    assertThat(paid.keySet()).containsExactlyInAnyOrder("CARD", "CASH", "UPI");
    assertThat(get("/api/v1/pos/reports/day?date=2020-01-01", adminToken()).getBody().get("bills")).isEqualTo(0);
    assertThat(((Number) get("/api/v1/admin/dashboard", adminToken()).getBody().get("posBillsToday")).longValue()).isGreaterThanOrEqualTo(1);
  }

  // ---------- admin set-up ----------
  @Test void adminManagesBarcodesAndCashiers() {

    String admin = adminToken();
    Object rice = riceId();
    var attrs = get("/api/v1/admin/products/" + rice + "/pos", admin);
    assertThat(attrs.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(attrs.getBody().get("soldBy")).isEqualTo("UNIT");

    Object dal = productByName(admin, "Toor Dal").get("id");
    var taken = call(HttpMethod.PUT, "/api/v1/admin/products/" + dal + "/pos", admin,
        Map.of("soldBy", "UNIT", "gstPercent", 5, "barcodes", List.of(ean("890100000001"))), null);
    assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(taken.getBody().get("code")).isEqualTo("BARCODE_TAKEN");

    var staffEmail = "staff-" + UUID.randomUUID().toString().substring(0, 6) + "@shop.in";
    var made = post("/api/v1/admin/staff", admin, Map.of("email", staffEmail, "name", "Night shift"));
    assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String staffToken = token(staffEmail);
    assertThat(get("/api/v1/pos/shop", staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getList("/api/v1/admin/staff", admin)).anyMatch(u -> staffEmail.equals(u.get("email")));

    var block = call(HttpMethod.PATCH, "/api/v1/admin/staff/" + made.getBody().get("id"), admin, Map.of("active", false), null);
    assertThat(block.getStatusCode()).isEqualTo(HttpStatus.OK);
    post("/api/v1/auth/otp/send", null, Map.of("email", staffEmail));
    var login = post("/api/v1/auth/otp/verify", null, Map.of("email", staffEmail, "otp", "12345", "deviceId", UUID.randomUUID().toString()));
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ---------- counter gaps: payment modes, quote discount, refund mode, receipts, search ----------
  private Map fieldsOf(org.springframework.http.ResponseEntity<Map> res) {
    return (Map) ((Map) res.getBody().get("details")).get("fields");
  }

  @Test void paymentModeIsTrimmedAndUpperCased_andABadOneQuotesWhatWasReceived() {
    String cashier = cashier();
    Map cart = Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1)));
    Map ok = sale(cashier, Map.of("cart", cart, "payments", List.of(Map.of("mode", " cash ", "amountPaise", 12500))), UUID.randomUUID().toString());
    assertThat(((Map) ((List) ok.get("payments")).get(0)).get("mode")).isEqualTo("CASH");

    var bad = call(HttpMethod.POST, "/api/v1/pos/sales", cashier,
        Map.of("cart", cart, "payments", List.of(Map.of("mode", "CASH", "amountPaise", 1), Map.of("mode", "xyz", "amountPaise", 12499))),
        UUID.randomUUID().toString());
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(bad.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    assertThat(fieldsOf(bad).get("payments[1].mode")).isEqualTo("payment mode must be CASH, UPI or CARD, or CREDIT on a bill (got \"xyz\")");

    java.util.Map<String, Object> noMode = new java.util.HashMap<>();
    noMode.put("amountPaise", 12500);
    var missing = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", cart, "payments", List.of(noMode)), UUID.randomUUID().toString());
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fieldsOf(missing)).containsKey("payments[0].mode");
  }

  @Test void shopInfoTellsWhetherRoundOffIsOn() {
    assertThat(get("/api/v1/pos/shop", cashier()).getBody().get("roundOffMode")).isEqualTo("NONE");
    assertThat(get("/api/v1/pos/shop", cashier()).getBody().get("shopName")).isNotNull();
  }

  @Test void quoteTakesTheManualDiscountSoThePreviewMatchesTheSale() {
    String cashier = cashier();
    Object rice = riceId();
    Map cart = Map.of("items", List.of(Map.of("productId", rice, "qty", 2)));
    Map previewBody = new java.util.HashMap<>(cart);
    previewBody.put("manualDiscount", Map.of("amountPaise", 1000)); // no reason and no PIN needed to preview
    var quote = post("/api/v1/pos/quote", cashier, previewBody).getBody();
    assertThat(quote.get("manualDiscountPaise")).isEqualTo(1000);
    assertThat(quote.get("totalPaise")).isEqualTo(24000);
    assertThat(post("/api/v1/pos/quote", cashier, cart).getBody().get("manualDiscountPaise")).isEqualTo(0);

    // the sale enforces the PIN: an admin gives the same discount and lands on the previewed total
    Map s = sale(adminToken(), Map.of("cart", cart, "manualDiscount", Map.of("amountPaise", 1000, "reason", "damaged pack"),
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", 24000))), UUID.randomUUID().toString());
    assertThat(s.get("totalPaise")).isEqualTo(quote.get("totalPaise"));
    var noPin = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", cart, "manualDiscount", Map.of("amountPaise", 1000, "reason", "x"),
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", 24000))), UUID.randomUUID().toString());
    assertThat(noPin.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

    Map negative = new java.util.HashMap<>(cart);
    negative.put("manualDiscount", Map.of("amountPaise", -1));
    assertThat(post("/api/v1/pos/quote", cashier, negative).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map tooMuch = new java.util.HashMap<>(cart);
    tooMuch.put("manualDiscount", Map.of("amountPaise", 25001));
    assertThat(post("/api/v1/pos/quote", cashier, tooMuch).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private Map upiBill(String cashier, int qty) {
    return sale(cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", qty))),
        "payments", List.of(Map.of("mode", "UPI", "amountPaise", 12500L * qty, "reference", "U1"))), UUID.randomUUID().toString());
  }

  private org.springframework.http.ResponseEntity<Map> giveBack(String cashier, Map bill, Map extra) {
    Map body = new java.util.HashMap<>(extra);
    body.put("items", List.of(Map.of("saleItemId", ((Map) ((List) bill.get("items")).get(0)).get("id"), "qty", 1)));
    return call(HttpMethod.POST, "/api/v1/pos/sales/" + bill.get("id") + "/returns", cashier, body, UUID.randomUUID().toString());
  }

  @Test void aReturnRefundsInTheBillsOwnModeUnlessToldOtherwise() {
    String cashier = cashier();
    Map bill = upiBill(cashier, 4);
    var byDefault = giveBack(cashier, bill, Map.of());
    assertThat(byDefault.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(((Map) ((List) byDefault.getBody().get("refunds")).get(0)).get("mode")).isEqualTo("UPI");
    var asCash = giveBack(cashier, bill, Map.of("refundMode", " cash "));
    assertThat(((Map) ((List) asCash.getBody().get("refunds")).get(0)).get("mode")).isEqualTo("CASH");
    var both = giveBack(cashier, bill, Map.of("refundMode", "CARD", "refunds", List.of(Map.of("mode", "CARD", "amountPaise", 12500))));
    assertThat(both.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fieldsOf(both)).containsKey("refundMode");
    var bogus = giveBack(cashier, bill, Map.of("refundMode", "credit"));
    assertThat(fieldsOf(bogus).get("refundMode")).isEqualTo("payment mode must be CASH, UPI or CARD (got \"credit\")");
    var explicit = giveBack(cashier, bill, Map.of("refunds", List.of(Map.of("mode", "cash", "amountPaise", 6000), Map.of("mode", "CARD", "amountPaise", 6500))));
    assertThat(explicit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var badLine = giveBack(cashier, bill, Map.of("refunds", List.of(Map.of("mode", "CASH", "amountPaise", 6000), Map.of("mode", "zzz", "amountPaise", 6500))));
    assertThat(fieldsOf(badLine).get("refunds[1].mode")).isEqualTo("payment mode must be CASH, UPI or CARD (got \"zzz\")");
  }

  @Test void aCashBillAndASplitBillRefundAsCash() {
    String cashier = cashier();
    Map cash = sale(cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 2))),
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", 10000), Map.of("mode", "UPI", "amountPaise", 15000))), UUID.randomUUID().toString());
    var r = giveBack(cashier, cash, Map.of());
    assertThat(((Map) ((List) r.getBody().get("refunds")).get(0)).get("mode")).isEqualTo("CASH");
  }

  @Test void creditNoteReceiptPrintsTheReturn() {
    String cashier = cashier();
    Map bill = upiBill(cashier, 2);
    var ret = giveBack(cashier, bill, Map.of("reason", "wrong pack"));
    String url = "/api/v1/pos/sales/" + bill.get("id") + "/returns/" + ret.getBody().get("id") + "/receipt";
    var receipt = get(url + "?width=32", cashier);
    assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(receipt.getBody().get("width")).isEqualTo(32);
    assertThat((String) receipt.getBody().get("text")).contains("CREDIT NOTE", (String) ret.getBody().get("creditNoteNo"),
        (String) bill.get("invoiceNo"), "Basmati Rice", "Returned 1", "Taxable", "CGST", "Refund total", "125.00", "Refund UPI", "wrong pack");
    assertThat(get(url + "?width=48", adminToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get(url + "?width=40", cashier).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get(url, cashier).getBody().get("width")).isEqualTo(32);
    assertThat(get("/api/v1/pos/sales/" + bill.get("id") + "/returns/" + UUID.randomUUID() + "/receipt", cashier).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get(url, token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test void counterSearchFindsABarcodeOrItemCodeExactlyAndShowsTheDisplayFields() {
    String cashier = cashier();
    var byBarcode = get("/api/v1/pos/products?q=" + ean("890100000001"), cashier);
    List<Map<String, Object>> items = (List<Map<String, Object>>) byBarcode.getBody().get("items");
    assertThat(items.get(0).get("name")).isEqualTo("Basmati Rice");
    assertThat(items.get(0).get("packSize")).isEqualTo("1 kg");
    assertThat(items.get(0).get("unit")).isEqualTo("kg");
    assertThat(items.get(0)).containsKey("variantName");
    assertThat((String) items.get(0).get("displayName")).startsWith("Basmati Rice").endsWith("1 kg");

    var byPlu = (List<Map<String, Object>>) get("/api/v1/pos/products?q=101", cashier).getBody().get("items");
    assertThat(byPlu.get(0).get("name")).isEqualTo("Loose Sugar");
    var byPluPadded = (List<Map<String, Object>>) get("/api/v1/pos/products?q=000101", cashier).getBody().get("items");
    assertThat(byPluPadded.get(0).get("name")).isEqualTo("Loose Sugar");
    var byName = (List<Map<String, Object>>) get("/api/v1/pos/products?q=basmati", cashier).getBody().get("items");
    assertThat(byName).anyMatch(p -> "Basmati Rice".equals(p.get("name")));
    assertThat(get("/api/v1/pos/products?q=" + ean("890199999999"), cashier).getBody().get("total")).isEqualTo(0);

    var scan = get("/api/v1/pos/barcode/" + ean("890100000001"), cashier).getBody();
    assertThat((String) ((Map) scan.get("product")).get("displayName")).endsWith("1 kg");
    assertThat(((Map) scan.get("product")).get("unit")).isEqualTo("kg");
  }

  @Test void updatedSinceReturnsOnlyWhatChangedAtOrAfterIt() {
    String cashier = cashier();
    assertThat(get("/api/v1/pos/products?updatedSince=" + java.time.Instant.now().plusSeconds(3600), cashier).getBody().get("total")).isEqualTo(0);
    var all = get("/api/v1/pos/products?updatedSince=2020-01-01T00:00:00Z&size=50", cashier).getBody();
    assertThat(((Number) all.get("total")).intValue()).isGreaterThan(3);
    // a plain date and a zone-less date-time (India time) are accepted like the offset form
    assertThat(get("/api/v1/pos/products?updatedSince=2020-01-01&size=50", cashier).getBody().get("total")).isEqualTo(all.get("total"));
    assertThat(get("/api/v1/pos/products?updatedSince=2020-01-01T05:30:00&size=50", cashier).getBody().get("total")).isEqualTo(all.get("total"));
    assertThat(get("/api/v1/pos/products?updatedSince=2999-01-01", cashier).getBody().get("total")).isEqualTo(0);
    assertThat(get("/api/v1/pos/products?updatedSince=garbage", cashier).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    // only the counter details (a new barcode) change: the product row itself is not touched, yet the till must refresh it
    String admin = adminToken();
    Object dal = productByName(admin, "Toor Dal").get("id");
    java.time.Instant before = java.time.Instant.now();
    String code = "DAL-" + UUID.randomUUID().toString().substring(0, 8);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/products/" + dal + "/pos", admin,
        Map.of("soldBy", "UNIT", "gstPercent", 5, "barcodes", List.of(code)), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    var changed = (List<Map<String, Object>>) get("/api/v1/pos/products?updatedSince=" + before + "&size=50", cashier).getBody().get("items");
    assertThat(changed).anyMatch(p -> dal.equals(p.get("productId")));
    var exactAndChanged = (List<Map<String, Object>>) get("/api/v1/pos/products?q=" + code + "&updatedSince=" + before, cashier).getBody().get("items");
    assertThat(exactAndChanged).hasSize(1);
    assertThat(exactAndChanged.get(0).get("productId")).isEqualTo(dal);
  }
}
