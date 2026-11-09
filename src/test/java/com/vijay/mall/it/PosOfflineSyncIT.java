package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.domain.Money;
import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.client.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** A counter that sold while offline catches up: real bills, the original sale time, safe to resend, nothing blocks anything. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PosOfflineSyncIT extends IntegrationTestBase {

  @BeforeEach
  void stock() { restock("Basmati Rice", 100); }

  private String cashier() { return token("cashier@vijaysupermall.local"); }

  private Object rice() {
    List<Map> products = (List<Map>) get("/api/v1/products?size=50&includeOutOfStock=true", cashier()).getBody().get("items");
    return products.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst().orElseThrow().get("id");
  }

  private int stockOf(Object pid) { return jdbc.queryForObject("select qty_on_hand from inventory where product_id = ?::uuid", Integer.class, pid.toString()); }

  private long priceOf(String token, Map cart) { return ((Number) post("/api/v1/pos/quote", token, cart).getBody().get("totalPaise")).longValue(); }

  private Map bill(String key, Instant soldAt, Map cart, long pay) {
    Map<String, Object> b = new HashMap<>();
    b.put("idempotencyKey", key);
    if (soldAt != null) b.put("soldAt", soldAt.toString());
    b.put("cart", cart);
    b.put("payments", List.of(Map.of("mode", "CASH", "amountPaise", pay)));
    return b;
  }

  private List<Map> upload(String token, List<Map> bills) {
    var res = post("/api/v1/pos/sync/sales", token, Map.of("sales", bills));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    return (List<Map>) res.getBody().get("results");
  }

  @Test
  void aBatchRecordsEachBillOnceKeepsTheOriginalTimeAndReportsEachOutcome() {
    String cashier = cashier();
    Object pid = rice();
    Map cart = Map.of("items", List.of(Map.of("productId", pid, "qty", 2)));
    long total = priceOf(cashier, cart);
    int before = stockOf(pid);
    Instant twoHoursAgo = Instant.now().minusSeconds(7200);
    String good = UUID.randomUUID().toString();
    String bad = UUID.randomUUID().toString();

    List<Map> bills = new ArrayList<>(List.of(bill(good, twoHoursAgo, cart, total), bill(bad, null, cart, total - 1), bill(good, twoHoursAgo, cart, total)));
    Map noKey = bill(" ", null, cart, total);
    bills.add(noKey);
    var results = upload(cashier, bills);

    assertThat(results.get(0).get("status")).isEqualTo("CREATED");
    assertThat((String) results.get(0).get("invoiceNo")).startsWith("VSM/");
    assertThat(results.get(1).get("status")).as("payments do not add up: refused on its own").isEqualTo("REJECTED");
    assertThat(results.get(1).get("errorCode")).isEqualTo("VALIDATION_FAILED");
    assertThat(results.get(2).get("status")).as("the same bill twice in one batch").isEqualTo("DUPLICATE");
    assertThat(results.get(2).get("saleId")).isEqualTo(results.get(0).get("saleId"));
    assertThat(results.get(3).get("status")).isEqualTo("REJECTED");
    assertThat(stockOf(pid)).as("only the one good bill took stock").isEqualTo(before - 2);

    var stored = get("/api/v1/pos/sales/" + results.get(0).get("saleId"), cashier).getBody();
    Instant createdAt = Instant.parse((String) stored.get("createdAt"));
    assertThat(createdAt).as("the till's own time, not the time of the upload").isBetween(twoHoursAgo.minusSeconds(2), twoHoursAgo.plusSeconds(2));

    var again = upload(cashier, List.of(bill(good, twoHoursAgo, cart, total)));
    assertThat(again.get(0).get("status")).as("resending the batch is safe").isEqualTo("DUPLICATE");
    assertThat(stockOf(pid)).isEqualTo(before - 2);
  }

  @Test
  void aBillForGoodsAlreadySoldIsRecordedEvenIfTheSystemThoughtStockWasShortAndTheAdminCanSeeIt() {
    String cashier = cashier();
    Object pid = rice();
    Map cart = Map.of("items", List.of(Map.of("productId", pid, "qty", 3)));
    long total = priceOf(cashier, cart);
    jdbc.update("update inventory set qty_on_hand = 1 where product_id = ?::uuid", pid.toString());

    var results = upload(cashier, List.of(bill(UUID.randomUUID().toString(), null, cart, total)));
    assertThat(results.get(0).get("status")).as("the money is in the drawer: the bill must exist").isEqualTo("CREATED");
    assertThat(stockOf(pid)).as("what was there is taken, never below zero").isZero();

    assertThat((List<Map>) results.get(0).get("exceptions")).extracting(e -> e.get("kind")).containsExactly("OVERSOLD");
    var audit = (List<Map>) get("/api/v1/admin/audit-log?action=OFFLINE_OVERSOLD&entity=pos_sale&size=5", adminToken()).getBody().get("items");
    assertThat(audit).anySatisfy(a -> assertThat(a.get("entityId")).isEqualTo(results.get(0).get("saleId")));
    var online = call(HttpMethod.POST, "/api/v1/pos/sales", cashier,
        Map.of("cart", cart, "payments", List.of(Map.of("mode", "CASH", "amountPaise", total))), UUID.randomUUID().toString());
    assertThat(online.getStatusCode()).as("a live bill is still refused when stock is short").isEqualTo(HttpStatus.CONFLICT);
    assertThat(online.getBody().get("code")).isEqualTo("OUT_OF_STOCK");
    restock("Basmati Rice", 100);
  }

  @Test
  void impossibleSaleTimesAndBadBatchesAreRefused() {
    String cashier = cashier();
    Map cart = Map.of("items", List.of(Map.of("productId", rice(), "qty", 1)));
    long total = priceOf(cashier, cart);
    var results = upload(cashier, List.of(bill(UUID.randomUUID().toString(), Instant.now().minusSeconds(10 * 86400L), cart, total),
        bill(UUID.randomUUID().toString(), Instant.now().plusSeconds(3600), cart, total)));
    assertThat(results).allSatisfy(r -> {
      assertThat(r.get("status")).isEqualTo("REJECTED");
      assertThat(r.get("errorCode")).isEqualTo("VALIDATION_FAILED");
    });
    assertThat(post("/api/v1/pos/sync/sales", cashier, Map.of("sales", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    List<Map> tooMany = new ArrayList<>();
    for (int i = 0; i < 51; i++) tooMany.add(bill(UUID.randomUUID().toString(), null, cart, total));
    assertThat(post("/api/v1/pos/sync/sales", cashier, Map.of("sales", tooMany)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void theStatusTellsTheTillWhenToDownloadTheProductsAgainAndOnlyStaffMayAsk() {
    String cashier = cashier();
    var first = get("/api/v1/pos/sync/status", cashier);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    Instant version = Instant.parse((String) first.getBody().get("catalogVersion"));
    assertThat(Instant.parse((String) first.getBody().get("serverTime"))).isAfter(Instant.now().minusSeconds(60));
    assertThat(first.getBody().get("batchLimit")).isEqualTo(50);
    assertThat(first.getBody().get("maxOfflineDays")).isEqualTo(7);

    jdbc.update("update product set updated_at = now() + interval '1 minute' where id = ?::uuid", rice().toString());
    Instant later = Instant.parse((String) get("/api/v1/pos/sync/status", cashier).getBody().get("catalogVersion"));
    assertThat(later).isAfter(version);

    assertThat(get("/api/v1/pos/sync/status", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(post("/api/v1/pos/sync/sales", token(freshEmail()), Map.of("sales", List.of())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ---------- bills sent with what the till really charged (rung) ----------

  private Map line(Object pid, int qty, long price, int gst, long discount) {
    Map<String, Object> l = new HashMap<>();
    l.put("productId", pid);
    l.put("qty", qty);
    l.put("unitPricePaise", price);
    l.put("gstPercent", gst);
    l.put("discountPaise", discount);
    return l;
  }

  private Map rungBill(String key, Instant soldAt, List<Map> lines, long pay) {
    Map<String, Object> b = new HashMap<>();
    b.put("idempotencyKey", key);
    if (soldAt != null) b.put("soldAt", soldAt.toString());
    b.put("rung", Map.of("lines", lines));
    b.put("payments", List.of(Map.of("mode", "CASH", "amountPaise", pay)));
    return b;
  }

  private int catalogGst(Object pid) {
    Map quote = post("/api/v1/pos/quote", cashier(), Map.of("items", List.of(Map.of("productId", pid, "qty", 1)))).getBody();
    return ((Number) ((Map) ((List) quote.get("lines")).get(0)).get("gstPercent")).intValue();
  }

  private long catalogPrice(Object pid) {
    return jdbc.queryForObject("select price_paise from product where id = ?::uuid", Long.class, pid.toString());
  }

  private List<Map> openExceptions() {
    return (List<Map>) get("/api/v1/admin/pos/offline-exceptions?status=OPEN&size=50", adminToken()).getBody().get("items");
  }

  @Test
  void aBillRungAtAnOldPriceIsRecordedAtThatPriceStockIsTakenAndTheManagerCanReviewTheDifferenceOnce() {
    String cashier = cashier();
    Object pid = rice();
    int gst = catalogGst(pid);
    long old = catalogPrice(pid);
    int before = stockOf(pid);
    jdbc.update("update product set price_paise = ? where id = ?::uuid", old + 1000, pid.toString());
    try {
      String key = UUID.randomUUID().toString();
      Instant soldAt = Instant.now().minusSeconds(3600);
      var r = upload(cashier, List.of(rungBill(key, soldAt, List.of(line(pid, 2, old, gst, 0)), 2 * old))).get(0);
      assertThat(r.get("status")).isEqualTo("CREATED");
      List<Map> found = (List<Map>) r.get("exceptions");
      assertThat(found).extracting(e -> e.get("kind")).containsExactly("PRICE_CHANGED");
      assertThat((String) found.get(0).get("detail")).contains(Money.plain(old)).contains(Money.plain(old + 1000));
      assertThat((String) r.get("invoiceNo")).contains(InvoiceNumbers.financialYear(soldAt));
      assertThat(stockOf(pid)).as("the goods left the shelf").isEqualTo(before - 2);

      var sale = get("/api/v1/pos/sales/" + r.get("saleId"), cashier).getBody();
      assertThat(((Number) sale.get("totalPaise")).longValue()).as("as rung, not at today's price").isEqualTo(2 * old);
      assertThat(((Number) ((Map) ((List) sale.get("items")).get(0)).get("unitPricePaise")).longValue()).isEqualTo(old);
      assertThat(jdbc.queryForObject("select needs_review from pos_sale where id = ?::uuid", Boolean.class, r.get("saleId").toString())).isTrue();
      String receipt = (String) get("/api/v1/pos/sales/" + r.get("saleId") + "/receipt?width=48", cashier).getBody().get("text");
      assertThat(receipt).contains(Money.plain(old)).contains(Money.plain(2 * old)).doesNotContain(Money.plain(2 * (old + 1000)));

      var again = upload(cashier, List.of(rungBill(key, soldAt, List.of(line(pid, 2, old, gst, 0)), 2 * old))).get(0);
      assertThat(again.get("status")).isEqualTo("DUPLICATE");
      assertThat(again.get("saleId")).isEqualTo(r.get("saleId"));
      assertThat((List<Map>) again.get("exceptions")).hasSize(1);
      assertThat(stockOf(pid)).isEqualTo(before - 2);

      // the manager's list: admin only
      Map mine = openExceptions().stream().filter(e -> r.get("saleId").equals(e.get("saleId"))).findFirst().orElseThrow();
      assertThat(mine.get("invoiceNo")).isEqualTo(r.get("invoiceNo"));
      assertThat(mine.get("kind")).isEqualTo("PRICE_CHANGED");
      assertThat(mine.get("cashierName")).isNotNull();
      assertThat(mine.get("productName")).isEqualTo("Basmati Rice");
      assertThat(mine.get("expected")).isEqualTo(String.valueOf(old + 1000));
      assertThat(mine.get("actual")).isEqualTo(String.valueOf(old));
      assertThat(Instant.parse((String) mine.get("soldAt"))).isBetween(soldAt.minusSeconds(2), soldAt.plusSeconds(2));
      String url = "/api/v1/admin/pos/offline-exceptions";
      assertThat(get(url, cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(get(url, token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(get(url, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(post(url + "/" + mine.get("id") + "/review", cashier, Map.of("note", "x")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(get(url + "?status=NOPE", adminToken()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

      var reviewed = post(url + "/" + mine.get("id") + "/review", adminToken(), Map.of("note", "price list was old"));
      assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(reviewed.getBody().get("reviewedAt")).isNotNull();
      assertThat(reviewed.getBody().get("reviewNote")).isEqualTo("price list was old");
      var second = post(url + "/" + mine.get("id") + "/review", adminToken(), Map.of("note", "again"));
      assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(second.getBody().get("code")).isEqualTo("ALREADY_REVIEWED");
      assertThat(post(url + "/" + UUID.randomUUID() + "/review", adminToken(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(openExceptions()).noneMatch(e -> e.get("id").equals(mine.get("id")));
      var done = (List<Map>) get(url + "?status=reviewed&size=50", adminToken()).getBody().get("items");
      assertThat(done).anyMatch(e -> e.get("id").equals(mine.get("id")));

      String scrape = RestClient.builder().baseUrl("http://localhost:" + managementPort).defaultStatusHandler(_ -> true, (_, _) -> { }).build()
          .get().uri("/actuator/prometheus").retrieve().body(String.class);
      assertThat(scrape).contains("mall_pos_offline_exceptions_open").contains("mall_pos_offline_exceptions_stale")
          .containsPattern("mall_pos_offline_exceptions_recorded_total\\{[^}]*kind=\"PRICE_CHANGED\"");
    } finally {
      jdbc.update("update product set price_paise = ? where id = ?::uuid", old, pid.toString());
    }
  }

  @Test
  void anAsRungBillWithDiscountsAndOversoldStockIsRecordedWithEveryException() {
    String cashier = cashier();
    Object pid = rice();
    int gst = catalogGst(pid);
    long price = catalogPrice(pid);
    jdbc.update("update inventory set qty_on_hand = 1 where product_id = ?::uuid", pid.toString());
    Map b = rungBill(UUID.randomUUID().toString(), null, List.of(line(pid, 3, price, gst, 100)), 3 * price - 100 - 50);
    b.put("manualDiscount", Map.of("amountPaise", 50, "reason", "regular customer"));
    var r = upload(cashier, List.of(b)).get(0);
    assertThat(r.get("status")).isEqualTo("CREATED");
    assertThat((List<Map>) r.get("exceptions")).extracting(e -> e.get("kind")).containsExactlyInAnyOrder("DISCOUNT", "DISCOUNT", "OVERSOLD");
    assertThat(stockOf(pid)).isZero();
    var sale = get("/api/v1/pos/sales/" + r.get("saleId"), cashier).getBody();
    assertThat(((Number) sale.get("totalPaise")).longValue()).isEqualTo(3 * price - 150);
    assertThat(((Number) sale.get("manualDiscountPaise")).longValue()).isEqualTo(50);
    var audit = (List<Map>) get("/api/v1/admin/audit-log?action=OFFLINE_OVERSOLD&entity=pos_sale&size=5", adminToken()).getBody().get("items");
    assertThat(audit).anySatisfy(a -> assertThat(a.get("entityId")).isEqualTo(r.get("saleId")));
    assertThat(openExceptions()).anyMatch(e -> r.get("saleId").equals(e.get("saleId")) && "OVERSOLD".equals(e.get("kind")));
    restock("Basmati Rice", 100);
  }

  @Test
  void aNewGstRateAndAProductSwitchedOffAreExceptionsNotRejections() {
    String cashier = cashier();
    Object pid = rice();
    int gst = catalogGst(pid);
    long price = catalogPrice(pid);
    jdbc.update("update product set active = false where id = ?::uuid", pid.toString());
    try {
      int till = gst == 18 ? 12 : 18;
      var r = upload(cashier, List.of(rungBill(UUID.randomUUID().toString(), null, List.of(line(pid, 1, price, till, 0)), price))).get(0);
      assertThat(r.get("status")).isEqualTo("CREATED");
      assertThat((List<Map>) r.get("exceptions")).extracting(e -> e.get("kind")).containsExactlyInAnyOrder("GST_CHANGED", "PRODUCT_INACTIVE");
      var sale = get("/api/v1/pos/sales/" + r.get("saleId"), cashier).getBody();
      assertThat(((Number) ((Map) ((List) sale.get("items")).get(0)).get("gstPercent")).intValue()).isEqualTo(till);
    } finally {
      jdbc.update("update product set active = true where id = ?::uuid", pid.toString());
    }
  }

  @Test
  void aBillThatMatchesTheCatalogHasNoExceptionsAndLegacyAndAsRungBillsMixInOneBatch() {
    String cashier = cashier();
    Object pid = rice();
    int gst = catalogGst(pid);
    long price = catalogPrice(pid);
    Map cart = Map.of("items", List.of(Map.of("productId", pid, "qty", 1)));
    var results = upload(cashier, List.of(rungBill(UUID.randomUUID().toString(), null, List.of(line(pid, 1, price, gst, 0)), price),
        bill(UUID.randomUUID().toString(), null, cart, priceOf(cashier, cart))));
    assertThat(results).extracting(x -> x.get("status")).containsExactly("CREATED", "CREATED");
    assertThat((List<Map>) results.get(0).get("exceptions")).isEmpty();
    assertThat((List<Map>) results.get(1).get("exceptions")).isEmpty();
  }

  @Test
  void aMalformedAsRungBillIsRejectedAndNothingIsRecorded() {
    String cashier = cashier();
    Object pid = rice();
    int gst = catalogGst(pid);
    long price = catalogPrice(pid);
    int before = stockOf(pid);
    Map withCoupon = rungBill(UUID.randomUUID().toString(), null, List.of(line(pid, 1, price, gst, 0)), price);
    withCoupon.put("cart", Map.of("customerMobile", "9000000001", "couponCode", "DIWALI"));
    Map withCredit = rungBill(UUID.randomUUID().toString(), null, List.of(line(pid, 1, price, gst, 0)), price);
    withCredit.put("payments", List.of(Map.of("mode", "CREDIT", "amountPaise", price)));
    withCredit.put("cart", Map.of("customerMobile", "9000000001"));
    var results = upload(cashier, List.of(
        rungBill(UUID.randomUUID().toString(), null, List.of(line(pid, 1, price, gst, 0)), price + 1),
        rungBill(UUID.randomUUID().toString(), null, List.of(line(UUID.randomUUID(), 1, price, gst, 0)), price),
        rungBill(UUID.randomUUID().toString(), null, List.of(line(pid, 0, price, gst, 0)), price),
        rungBill(UUID.randomUUID().toString(), null, List.of(line(pid, 1, -1, gst, 0)), price),
        rungBill(UUID.randomUUID().toString(), null, List.of(line(pid, 1, price, 7, 0)), price),
        rungBill(UUID.randomUUID().toString(), Instant.now().minusSeconds(10 * 86400L), List.of(line(pid, 1, price, gst, 0)), price),
        rungBill(" ", null, List.of(line(pid, 1, price, gst, 0)), price),
        withCoupon, withCredit));
    assertThat(results).allSatisfy(x -> {
      assertThat(x.get("status")).isEqualTo("REJECTED");
      assertThat(x.get("errorCode")).isEqualTo("VALIDATION_FAILED");
      assertThat((List<Map>) x.get("exceptions")).isEmpty();
    });
    assertThat((String) results.get(7).get("message")).contains("coupon");
    assertThat((String) results.get(8).get("message")).contains("credit");
    assertThat(stockOf(pid)).isEqualTo(before);
  }

  @Test
  void anOfflineBillWithASpacedModeIsRecordedAndOneWithAnUnknownModeIsRefusedWithTheValue() {
    String cashier = cashier();
    Object pid = rice();
    Map cart = Map.of("items", List.of(Map.of("productId", pid, "qty", 1)));
    long total = priceOf(cashier, cart);
    String good = UUID.randomUUID().toString();
    String bad = UUID.randomUUID().toString();
    Map spaced = bill(good, null, cart, total);
    spaced.put("payments", List.of(Map.of("mode", " cash ", "amountPaise", total)));
    Map unknown = bill(bad, null, cart, total);
    unknown.put("payments", List.of(Map.of("mode", "xyz", "amountPaise", total)));
    var results = upload(cashier, List.of(spaced, unknown));
    assertThat(results.get(0).get("status")).isEqualTo("CREATED");
    var stored = get("/api/v1/pos/sales/" + results.get(0).get("saleId"), cashier).getBody();
    assertThat(((Map) ((List) stored.get("payments")).get(0)).get("mode")).isEqualTo("CASH");
    assertThat(results.get(1).get("status")).isEqualTo("REJECTED");
    assertThat(results.get(1).get("errorCode")).isEqualTo("VALIDATION_FAILED");
    assertThat((String) results.get(1).get("message")).contains("(got \"xyz\")");
  }
}
