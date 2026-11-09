package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Cancelling puts stock back; customers cannot touch other people's orders or cancel late. */
@SuppressWarnings({"unchecked", "rawtypes"})
class OrderCancelIT extends IntegrationTestBase {

  @SuppressWarnings({"unchecked", "rawtypes"})
  private String place(String token, Object productId, int qty) {
    var items = List.of(Map.of("productId", productId, "qty", qty));
    Map<String, Object> req = Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP");
    long total = ((Number) post("/api/v1/cart/quote", token, req).getBody().get("totalPaise")).longValue();
    var res = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", items, "coinMode", "NONE",
        "fulfilment", "PICKUP", "paymentMethod", "COD", "expectedTotalPaise", total), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  private int stock(String admin, Object pid) {
    return ((Number) get("/api/v1/admin/products/" + pid, admin).getBody().get("qtyOnHand")).intValue();
  }

  @Test
  void cancelRestoresStock() {
    String admin = adminToken();
    String cust = token(freshEmail());
    Object pid = productByName(cust, "Potato Chips").get("id");
    int before = stock(admin, pid);

    String id = place(cust, pid, 2);
    assertThat(stock(admin, pid)).as("a placed order takes no stock yet").isEqualTo(before);
    post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CONFIRMED"));
    assertThat(stock(admin, pid)).as("confirming takes it").isEqualTo(before - 2);

    var res = post("/api/v1/orders/" + id + "/cancel", cust, null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().get("status")).isEqualTo("CANCELLED");
    assertThat(stock(admin, pid)).isEqualTo(before);
  }

  @Test
  void cancellingBeforeConfirmationNeverTouchesStock() {
    String admin = adminToken();
    String cust = token(freshEmail());
    Object pid = productByName(cust, "Potato Chips").get("id");
    int before = stock(admin, pid);
    String id = place(cust, pid, 2);
    assertThat(post("/api/v1/orders/" + id + "/cancel", cust, null).getBody().get("status")).isEqualTo("CANCELLED");
    assertThat(stock(admin, pid)).isEqualTo(before);
  }

  @Test
  void rejectingAnOrderExplainsWhyAndGivesStockBack() {
    String admin = adminToken();
    String cust = token(freshEmail());
    Object pid = productByName(cust, "Potato Chips").get("id");
    int before = stock(admin, pid);
    String id = place(cust, pid, 2);
    post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CONFIRMED"));

    assertThat(post("/api/v1/admin/orders/" + id + "/reject", admin, Map.of("reason", "")).getStatusCode().is4xxClientError()).isTrue();
    var res = post("/api/v1/admin/orders/" + id + "/reject", admin, Map.of("reason", "sold out at the counter"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).containsEntry("status", "CANCELLED");
    assertThat(res.getBody().get("cancelReason").toString()).contains("sold out at the counter");
    assertThat(stock(admin, pid)).isEqualTo(before);
    assertThat(post("/api/v1/admin/orders/" + id + "/reject", admin, Map.of("reason", "again")).getStatusCode().is4xxClientError())
        .as("a closed order cannot be rejected twice").isTrue();
  }

  @Test
  void cancellingThroughTheStatusEndpointShowsTheStaffNoteAsTheReason() {
    String admin = adminToken();
    String cust = token(freshEmail());
    String id = place(cust, productByName(cust, "Potato Chips").get("id"), 1);
    var res = post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CANCELLED", "note", "  shop closed today "));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).containsEntry("cancelReason", "shop closed today").containsEntry("cancelledBy", "SHOP")
        .containsEntry("cancelCode", "CANCELLED_BY_SHOP");
    var mine = get("/api/v1/orders/" + id, cust).getBody();
    assertThat(mine).containsEntry("cancelReason", "shop closed today").containsEntry("cancelledBy", "SHOP")
        .containsEntry("cancelCode", "CANCELLED_BY_SHOP");
  }

  @Test
  void cancellingThroughTheStatusEndpointWithoutANoteStillSaysWhoDidIt() {
    String admin = adminToken();
    String cust = token(freshEmail());
    String id = place(cust, productByName(cust, "Potato Chips").get("id"), 1);
    var res = post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CANCELLED"));
    assertThat(res.getBody().get("cancelReason")).isNull();
    assertThat(res.getBody()).containsEntry("cancelledBy", "SHOP").containsEntry("cancelCode", "CANCELLED_BY_SHOP");
  }

  @Test
  void aRejectedOrderShowsTheRawReasonAndWhoRejectedIt() {
    String admin = adminToken();
    String cust = token(freshEmail());
    String id = place(cust, productByName(cust, "Potato Chips").get("id"), 1);
    var res = post("/api/v1/admin/orders/" + id + "/reject", admin, Map.of("reason", "sold out"));
    assertThat(res.getBody()).containsEntry("cancelReason", "sold out").containsEntry("cancelledBy", "SHOP")
        .containsEntry("cancelCode", "REJECTED_BY_SHOP");
    assertThat(get("/api/v1/orders/" + id, cust).getBody()).containsEntry("cancelReason", "sold out");
    var listed = (List<Map<String, Object>>) get("/api/v1/orders?size=5", cust).getBody().get("items");
    assertThat(listed).anyMatch(o -> id.equals(o.get("id")) && "REJECTED_BY_SHOP".equals(o.get("cancelCode")));
  }

  @Test
  void anOldRejectedRowStillShowsCleanTextWithoutThePrefix() {
    String cust = token(freshEmail());
    String id = place(cust, productByName(cust, "Potato Chips").get("id"), 1);
    jdbc.update("update orders set status = 'CANCELLED', cancel_reason = 'Rejected by shop: no stock', cancelled_by = 'SHOP', "
        + "cancel_code = 'REJECTED_BY_SHOP' where id = ?::uuid", id);
    assertThat(get("/api/v1/orders/" + id, cust).getBody()).containsEntry("cancelReason", "no stock");
  }

  @Test
  void aCustomerCancelRecordsTheCustomerAndTheirReason() {
    String cust = token(freshEmail());
    String id = place(cust, productByName(cust, "Potato Chips").get("id"), 1);
    var res = post("/api/v1/orders/" + id + "/cancel", cust, Map.of("reason", "changed my mind"));
    assertThat(res.getBody()).containsEntry("cancelledBy", "CUSTOMER").containsEntry("cancelCode", "CUSTOMER_CANCELLED")
        .containsEntry("cancelReason", "changed my mind");
    String other = place(cust, productByName(cust, "Potato Chips").get("id"), 1);
    var plain = post("/api/v1/orders/" + other + "/cancel", cust, null);
    assertThat(plain.getBody().get("cancelReason")).isNull();
    assertThat(plain.getBody()).containsEntry("cancelledBy", "CUSTOMER").containsEntry("cancelCode", "CUSTOMER_CANCELLED");
    assertThat(get("/api/v1/orders/" + id, cust).getBody()).containsEntry("cancelledBy", "CUSTOMER");
  }

  @Test
  void anOrderThatIsNotCancelledHasNoCancelFields() {
    String cust = token(freshEmail());
    String id = place(cust, productByName(cust, "Potato Chips").get("id"), 1);
    var body = get("/api/v1/orders/" + id, cust).getBody();
    assertThat(body.get("cancelledBy")).isNull();
    assertThat(body.get("cancelCode")).isNull();
  }

  @Test
  void confirmingFailsWhenTheShelfWasEmptiedAtTheCounter() {
    String admin = adminToken();
    String cust = token(freshEmail());
    Object pid = productByName(cust, "Potato Chips").get("id");
    String id = place(cust, pid, 2);
    jdbc.update("update inventory set qty_on_hand = 1 where product_id = ?::uuid", pid.toString());
    var res = post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", "CONFIRMED"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code")).isEqualTo("OUT_OF_STOCK");
    assertThat(get("/api/v1/orders/" + id, cust).getBody().get("status")).isEqualTo("PLACED");
    assertThat(stock(admin, pid)).isEqualTo(1);
    restock("Potato Chips", 80);
  }

  @Test
  void otherCustomerCannotSeeOrCancel() {
    String owner = token(freshEmail());
    String other = token(freshEmail());
    String id = place(owner, productByName(owner, "Digestive Biscuits").get("id"), 1);
    assertThat(get("/api/v1/orders/" + id, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post("/api/v1/orders/" + id + "/cancel", other, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void cannotCancelOncePacking() {
    String admin = adminToken();
    String cust = token(freshEmail());
    String id = place(cust, productByName(cust, "Bath Soap").get("id"), 1);
    List.of("CONFIRMED", "PACKED").forEach(s -> post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", s)));
    var res = post("/api/v1/orders/" + id + "/cancel", cust, null);
    assertThat(res.getStatusCode().is4xxClientError()).isTrue();
  }
}
