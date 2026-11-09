package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * A return marked "defective" is only a claim: the goods wait for a review, are not on the shelf meanwhile, and staff or the owner
 * decide. Confirmed defective goods are held (never restocked); goods that were fine are added back to inventory.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class DefectiveStockIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  @BeforeEach
  void stock() {
    restock("Orange Juice", 60);
    restock("Basmati Rice", 100);
  }

  private int onHand(Object pid) {
    return jdbc.queryForObject("select qty_on_hand from inventory where product_id = ?::uuid", Integer.class, pid.toString());
  }

  private long units(Object pid, String status) {
    return jdbc.queryForObject("select coalesce(sum(qty), 0) from defective_stock where product_id = ?::uuid and status = ?", Long.class, pid.toString(), status);
  }

  private String deliveredOrder(Object pid, String token, int qty) {
    String admin = adminToken();
    var items = List.of(Map.of("productId", pid, "qty", qty));
    long total = ((Number) post("/api/v1/cart/quote", token, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP")).getBody().get("totalPaise")).longValue();
    var res = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP", "paymentMethod", "COD",
        "expectedTotalPaise", total), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) res.getBody().get("id");
    for (String s : List.of("CONFIRMED", "PICKED_UP")) post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", s));
    return id;
  }

  private String claimBatchId(Object pid) {
    List<Map> items = (List<Map>) get("/api/v1/pos/stock/defective?productId=" + pid + "&size=50", adminToken()).getBody().get("items");
    return (String) items.get(0).get("id");
  }

  @Test
  void aDefectiveClaimWaitsForReviewAndIsNotOnTheShelfMeanwhile() {
    String admin = adminToken();
    String cust = token(freshEmail());
    Object pid = productByName(cust, "Orange Juice").get("id");
    String order = deliveredOrder(pid, cust, 3);
    int afterSale = onHand(pid);
    long pendingBefore = units(pid, "PENDING_REVIEW");
    long heldBefore = units(pid, "HELD");

    var ret = call(HttpMethod.POST, "/api/v1/admin/orders/" + order + "/returns", admin,
        Map.of("reason", "bottle leaking", "items", List.of(Map.of("productId", pid, "qty", 2, "restock", false))), UUID.randomUUID().toString());
    assertThat(ret.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(((Number) ret.getBody().get("refundPaise")).longValue()).as("the customer is refunded straight away").isPositive();
    assertThat(onHand(pid)).as("not on the shelf while the claim is open").isEqualTo(afterSale);
    assertThat(units(pid, "PENDING_REVIEW")).isEqualTo(pendingBefore + 2);
    assertThat(units(pid, "HELD")).as("and not yet counted as defective").isEqualTo(heldBefore);

    var ok = call(HttpMethod.POST, "/api/v1/admin/orders/" + order + "/returns", admin,
        Map.of("reason", "changed mind", "items", List.of(Map.of("productId", pid, "qty", 1))), UUID.randomUUID().toString());
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(onHand(pid)).as("an ordinary return goes straight back on the shelf").isEqualTo(afterSale + 1);
    assertThat(units(pid, "PENDING_REVIEW")).isEqualTo(pendingBefore + 2);

    var queue = get("/api/v1/pos/stock/defective?productId=" + pid, admin);
    assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map> items = (List<Map>) queue.getBody().get("items");
    assertThat(items.get(0)).containsEntry("source", "ORDER_RETURN").containsEntry("status", "PENDING_REVIEW").containsEntry("note", "bottle leaking");
    assertThat(getList("/api/v1/admin/stock/defective/summary", admin)).anySatisfy(r -> {
      assertThat(r.get("productId")).isEqualTo(pid.toString());
      assertThat(((Number) r.get("pendingReviewQty")).longValue()).isEqualTo(pendingBefore + 2);
    });
  }

  @Test
  void staffConfirmTheClaimAndItMovesToTheDefectiveListWhereTheOwnerWritesItOff() {
    String admin = adminToken();
    String cashier = token(CASHIER);
    Object pid = productByName(admin, "Orange Juice").get("id");
    UUID batch = UUID.randomUUID();
    jdbc.update("insert into defective_stock (id, product_id, qty, source, note, created_at) values (?, ?::uuid, 4, 'POS_RETURN', 'x', now())", batch, pid.toString());
    int shelf = onHand(pid);

    assertThat(call(HttpMethod.POST, "/api/v1/admin/stock/defective/" + batch + "/resolve", admin, Map.of("action", "WRITE_OFF"), null).getStatusCode())
        .as("it must be confirmed defective before it can be written off").isEqualTo(HttpStatus.CONFLICT);

    var confirmed = call(HttpMethod.POST, "/api/v1/pos/stock/defective/" + batch + "/review", cashier, Map.of("decision", "confirm_defective", "note", "seal broken"), null);
    assertThat(confirmed.getStatusCode()).as(String.valueOf(confirmed.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(confirmed.getBody()).containsEntry("status", "HELD").containsEntry("reviewNote", "seal broken");
    assertThat(confirmed.getBody().get("reviewedBy")).isNotNull();
    assertThat(onHand(pid)).as("confirmed defective goods are never added to the shelf").isEqualTo(shelf);

    assertThat(call(HttpMethod.POST, "/api/v1/pos/stock/defective/" + batch + "/review", admin, Map.of("decision", "RESTOCK"), null).getStatusCode())
        .as("reviewed once").isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/stock/defective/" + batch + "/resolve", cashier, Map.of("action", "WRITE_OFF"), null).getStatusCode())
        .as("the final decision is the owner's").isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/stock/defective/" + batch + "/resolve", admin, Map.of("action", "RESTOCK"), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    var done = call(HttpMethod.POST, "/api/v1/admin/stock/defective/" + batch + "/resolve", admin, Map.of("action", "return_to_supplier", "note", "credit note 77"), null);
    assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(done.getBody()).containsEntry("status", "RETURNED_TO_SUPPLIER").containsEntry("resolveNote", "credit note 77");
    assertThat(onHand(pid)).isEqualTo(shelf);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/stock/defective/" + batch + "/resolve", admin, Map.of("action", "WRITE_OFF"), null).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    jdbc.update("delete from defective_stock where id = ?", batch);
  }

  @Test
  void aClaimThatTurnsOutFineIsAddedBackToInventory() {
    String admin = adminToken();
    Object pid = productByName(admin, "Orange Juice").get("id");
    UUID batch = UUID.randomUUID();
    jdbc.update("insert into defective_stock (id, product_id, qty, source, created_at) values (?, ?::uuid, 3, 'ORDER_RETURN', now())", batch, pid.toString());
    int shelf = onHand(pid);

    var res = call(HttpMethod.POST, "/api/v1/pos/stock/defective/" + batch + "/review", token(CASHIER), Map.of("decision", "RESTOCK", "note", "checked, sealed and fine"), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).containsEntry("status", "RESTOCKED");
    assertThat(onHand(pid)).as("not defective after all: back on the shelf").isEqualTo(shelf + 3);
    assertThat(jdbc.queryForObject("select count(*) from stock_movement where ref_id = ? and reason = 'DEFECTIVE_REVIEW_RESTOCK'", Integer.class, batch)).isEqualTo(1);
    assertThat(call(HttpMethod.POST, "/api/v1/pos/stock/defective/" + batch + "/review", admin, Map.of("decision", "RESTOCK"), null).getStatusCode())
        .as("a batch cannot be restocked twice").isEqualTo(HttpStatus.CONFLICT);
    assertThat(onHand(pid)).isEqualTo(shelf + 3);
    jdbc.update("delete from stock_movement where ref_id = ?", batch);
    jdbc.update("delete from defective_stock where id = ?", batch);
    restock("Orange Juice", 60);
  }

  @Test
  void aDefectiveCounterReturnAlsoWaitsForReview() {
    String cashier = token(CASHIER);
    List<Map<String, Object>> products = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", cashier).getBody().get("items");
    Object rice = products.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst().orElseThrow().get("id");
    var sale = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", rice, "qty", 3))),
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", 37500))), UUID.randomUUID().toString());
    assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Object lineId = ((List<Map>) sale.getBody().get("items")).get(0).get("id");
    int shelf = onHand(rice);
    long pendingBefore = units(rice, "PENDING_REVIEW");

    var back = call(HttpMethod.POST, "/api/v1/pos/sales/" + sale.getBody().get("id") + "/returns", cashier,
        Map.of("items", List.of(Map.of("saleItemId", lineId, "qty", 2, "restock", false)), "reason", "torn bag"), UUID.randomUUID().toString());
    assertThat(back.getStatusCode()).as(String.valueOf(back.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(onHand(rice)).isEqualTo(shelf);
    assertThat(units(rice, "PENDING_REVIEW")).isEqualTo(pendingBefore + 2);

    var fine = call(HttpMethod.POST, "/api/v1/pos/sales/" + sale.getBody().get("id") + "/returns", cashier,
        Map.of("items", List.of(Map.of("saleItemId", lineId, "qty", 1)), "reason", "wrong item"), UUID.randomUUID().toString());
    assertThat(fine.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(onHand(rice)).isEqualTo(shelf + 1);

    // the cashier can look at the queue, but cannot confirm as defective the goods they took back themselves
    String batch = claimBatchId(rice);
    var selfConfirm = call(HttpMethod.POST, "/api/v1/pos/stock/defective/" + batch + "/review", cashier, Map.of("decision", "CONFIRM_DEFECTIVE"), null);
    assertThat(selfConfirm.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(selfConfirm.getBody()).containsEntry("code", "DEFECTIVE_SELF_REVIEW");
    var review = call(HttpMethod.POST, "/api/v1/pos/stock/defective/" + batch + "/review", cashier, Map.of("decision", "RESTOCK"), null);
    assertThat(review.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(onHand(rice)).isEqualTo(shelf + 3);
  }

  @Test
  void validationAndAccessRules() {
    String admin = adminToken();
    String cashier = token(CASHIER);
    UUID batch = UUID.randomUUID();
    assertThat(call(HttpMethod.POST, "/api/v1/pos/stock/defective/" + batch + "/review", cashier, Map.of("decision", "WRITE_OFF"), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, "/api/v1/pos/stock/defective/" + batch + "/review", cashier, Map.of("decision", "RESTOCK", "note", "n".repeat(201)), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, "/api/v1/pos/stock/defective/" + batch + "/review", cashier, Map.of("decision", "RESTOCK"), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/stock/defective/" + batch + "/resolve", admin, Map.of("action", "WRITE_OFF"), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/stock/defective?status=NOPE", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/pos/stock/defective?status=NOPE", cashier).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/stock/defective?status=RESTOCKED&size=500", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/pos/stock/defective", cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/admin/stock/defective", cashier).getStatusCode()).as("the full report is the owner's").isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/stock/defective/summary", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/pos/stock/defective", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/pos/stock/defective", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
