package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    var audit = (List<Map>) get("/api/v1/admin/audit-log?action=OFFLINE_OVERSOLD&entity=pos_sale&size=5", adminToken()).getBody().get("items");
    assertThat(audit).anySatisfy(a -> assertThat(a.get("entityId")).isEqualTo(results.get(0).get("saleId")));
    var online = call(org.springframework.http.HttpMethod.POST, "/api/v1/pos/sales", cashier,
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
}
