package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.infra.PaymentRepository;
import com.vijay.mall.support.FakeGateway;
import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Online payment lifecycle with a second provider (FakeGateway) wired in through the same port as Razorpay. */
@Import(OnlinePaymentIT.FakeProvider.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class OnlinePaymentIT extends IntegrationTestBase {

  @TestConfiguration
  static class FakeProvider {
    @Bean FakeGateway fakeGateway() { return new FakeGateway(); }
  }

  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry r) { r.add("app.payment.online-provider", () -> "fake"); }

  @Autowired FakeGateway fake;
  @Autowired OrderService orders;
  @Autowired PaymentService payments;
  @Autowired PaymentRepository paymentRepo;

  @org.junit.jupiter.api.BeforeEach
  void enableOnlinePayment() {
    restock("Orange Juice", 20);
    // ONLINE ships switched off; the shop owner turns it on from the admin screen
    call(HttpMethod.PUT, "/api/v1/admin/payment-methods", adminToken(),
        List.of(Map.of("code", "ONLINE", "enabled", true)), null);
  }

  private record Placed(String token, String orderId, long total, Object productId) {}

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Placed placeOnline(String productName, int qty) {
    String token = token(freshEmail());
    Object pid = productByName(token, productName).get("id");
    var items = List.of(Map.of("productId", pid, "qty", qty));
    Map<String, Object> req = Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP");
    long total = ((Number) post("/api/v1/cart/quote", token, req).getBody().get("totalPaise")).longValue();
    var res = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", items, "coinMode", "NONE",
        "fulfilment", "PICKUP", "paymentMethod", "ONLINE", "expectedTotalPaise", total), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(res.getBody().get("status")).isEqualTo("PAYMENT_PENDING");
    assertThat(res.getBody().get("paymentSession")).isNotNull();
    return new Placed(token, (String) res.getBody().get("id"), total, pid);
  }

  @SuppressWarnings("rawtypes")
  private org.springframework.http.ResponseEntity<Map> webhook(String signature, Map<String, Object> body) {
    return client().post().uri("/api/v1/payments/webhook/fake").contentType(MediaType.APPLICATION_JSON)
        .headers(h -> { if (signature != null) h.set("x-fake-signature", signature); }).body(body).retrieve().toEntity(Map.class);
  }

  private org.springframework.http.ResponseEntity<Map> event(String id, String type, String orderId, long amount) {
    return webhook("ok", Map.of("eventId", id, "type", type, "providerOrderId", "fake_" + orderId, "amount", amount));
  }

  private Map<String, String> order(Placed p) { return get("/api/v1/orders/" + p.orderId(), p.token()).getBody(); }

  private int stock(Object pid) {
    return ((Number) get("/api/v1/admin/products/" + pid, adminToken()).getBody().get("qtyOnHand")).intValue();
  }

  @Test
  void capturedPaymentPlacesTheOrderAndDuplicateEventsAreHarmless() {
    var p = placeOnline("Orange Juice", 1);
    String evId = "ev-" + UUID.randomUUID();
    assertThat(event(evId, "captured", p.orderId(), p.total()).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(order(p)).containsEntry("status", "PLACED").containsEntry("paymentStatus", "CAPTURED");
    assertThat(event(evId, "captured", p.orderId(), p.total()).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(order(p)).containsEntry("status", "PLACED");
  }

  /** Regression: a failed attempt used to close the order, so a successful retry was charged but never honoured. */
  @Test
  void aFailedAttemptKeepsTheOrderOpenSoARetryCanStillPay() {
    int before = stock(placeOnlineProductId());
    var p = placeOnline("Orange Juice", 2);
    assertThat(stock(p.productId())).as("placing takes no stock").isEqualTo(before);
    event("ev-" + UUID.randomUUID(), "failed", p.orderId(), 0);
    assertThat(order(p)).containsEntry("status", "PAYMENT_PENDING");
    int refundsBefore = fake.refunds.size();
    event("ev-" + UUID.randomUUID(), "captured", p.orderId(), p.total());
    assertThat(order(p)).containsEntry("status", "PLACED").containsEntry("paymentStatus", "CAPTURED");
    assertThat(fake.refunds).as("nothing to refund: the order is honoured").hasSize(refundsBefore);
  }

  @Test
  void rejectingAPaidOrderRefundsTheCustomerInFull() {
    String admin = adminToken();
    var p = placeOnline("Orange Juice", 1);
    event("ev-" + UUID.randomUUID(), "captured", p.orderId(), p.total());
    post("/api/v1/admin/orders/" + p.orderId() + "/status", admin, Map.of("status", "CONFIRMED"));
    int refundsBefore = fake.refunds.size();
    var res = post("/api/v1/admin/orders/" + p.orderId() + "/reject", admin, Map.of("reason", "cannot deliver today"));
    assertThat(res.getBody()).containsEntry("status", "CANCELLED").containsEntry("paymentStatus", "REFUNDED");
    assertThat(fake.refunds).hasSize(refundsBefore + 1);
    assertThat(fake.refunds.get(fake.refunds.size() - 1)).endsWith(":" + p.total());
  }

  @Test
  void staffCannotMoveAnUnpaidOnlineOrderForward() {
    var p = placeOnline("Orange Juice", 1);
    for (String target : List.of("PLACED", "DELIVERED")) {
      var res = post("/api/v1/admin/orders/" + p.orderId() + "/status", adminToken(), Map.of("status", target));
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    assertThat(order(p)).containsEntry("status", "PAYMENT_PENDING");
  }

  private Object placeOnlineProductId() { return productByName(adminToken(), "Orange Juice").get("id"); }

  @Test
  void wrongAmountIsNotAcceptedAsPayment() {
    var p = placeOnline("Orange Juice", 1);
    int refundsBefore = fake.refunds.size();
    event("ev-" + UUID.randomUUID(), "captured", p.orderId(), p.total() - 1);
    assertThat(order(p)).containsEntry("status", "PAYMENT_PENDING");
    assertThat(fake.refunds).as("the odd amount that did arrive is sent back").hasSize(refundsBefore + 1);
  }

  @Test
  void badSignatureIsRejectedAndNothingChanges() {
    var p = placeOnline("Orange Juice", 1);
    var res = webhook("forged", Map.of("eventId", "x" + UUID.randomUUID(), "type", "captured",
        "providerOrderId", "fake_" + p.orderId(), "amount", p.total()));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(res.getBody().get("code")).isEqualTo("BAD_SIGNATURE");
    assertThat(order(p)).containsEntry("status", "PAYMENT_PENDING");
  }

  @Test
  void unknownProviderInTheUrlIs404() {
    var res = client().post().uri("/api/v1/payments/webhook/nobody").contentType(MediaType.APPLICATION_JSON).body("{}")
        .retrieve().toEntity(Map.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void cancellingAPaidOrderRefundsIt() {
    var p = placeOnline("Orange Juice", 1);
    event("ev-" + UUID.randomUUID(), "captured", p.orderId(), p.total());
    var cancel = post("/api/v1/orders/" + p.orderId() + "/cancel", p.token(), null);
    assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(cancel.getBody()).containsEntry("status", "CANCELLED").containsEntry("paymentStatus", "REFUNDED");
    assertThat(fake.refunds).anyMatch(r -> r.endsWith(":" + p.total()));
  }

  @Test
  void cancellingBeforePaymentMarksThePaymentFailedNotRefunded() {
    var p = placeOnline("Orange Juice", 1);
    var cancel = post("/api/v1/orders/" + p.orderId() + "/cancel", p.token(), null);
    assertThat(cancel.getBody()).containsEntry("status", "CANCELLED").containsEntry("paymentStatus", "FAILED");
  }

  @Test
  void unpaidOrdersExpireWithoutEverTouchingStock() {
    int before = stock(placeOnlineProductId());
    var p = placeOnline("Orange Juice", 3);
    assertThat(stock(p.productId())).isEqualTo(before);
    jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?::uuid", java.sql.Timestamp.from(Instant.now().minusSeconds(60)), p.orderId());
    orders.expireUnpaid();
    assertThat(order(p)).containsEntry("status", "CANCELLED");
    assertThat(stock(p.productId())).isEqualTo(before);
  }

  @Test
  void paymentArrivingAfterExpiryIsRefunded() {
    var p = placeOnline("Orange Juice", 1);
    jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?::uuid", java.sql.Timestamp.from(Instant.now().minusSeconds(60)), p.orderId());
    orders.expireUnpaid();
    int refundsBefore = fake.refunds.size();
    event("ev-" + UUID.randomUUID(), "captured", p.orderId(), p.total());
    assertThat(order(p)).containsEntry("status", "CANCELLED").containsEntry("paymentStatus", "REFUNDED");
    assertThat(fake.refunds).hasSize(refundsBefore + 1);
  }

  @Test
  void reconcileLeavesPaymentsAloneWhenTheProviderHasNothingToReport() {
    var p = placeOnline("Orange Juice", 1);
    jdbc.update("UPDATE payment SET created_at = ? WHERE order_id = ?::uuid", java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), p.orderId());
    payments.reconcile();
    assertThat(order(p)).containsEntry("status", "PAYMENT_PENDING");
  }

  @Test
  void adminCanSwitchOnlinePaymentOffAndOn() {
    String admin = adminToken();
    var list = getList("/api/v1/admin/payment-methods", admin);
    assertThat(list).extracting(m -> m.get("code")).contains("COD", "ONLINE");

    var off = call(HttpMethod.PUT, "/api/v1/admin/payment-methods", admin,
        List.of(Map.of("code", "ONLINE", "enabled", false)), null);
    assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
    try {
      String token = token(freshEmail());
      assertThat(getList("/api/v1/payment-methods", token)).extracting(m -> m.get("code")).doesNotContain("ONLINE");
      Object pid = productByName(token, "Orange Juice").get("id");
      var items = List.of(Map.of("productId", pid, "qty", 1));
      var res = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", items, "fulfilment", "PICKUP",
          "coinMode", "NONE", "paymentMethod", "ONLINE", "expectedTotalPaise", 1), UUID.randomUUID().toString());
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    } finally {
      call(HttpMethod.PUT, "/api/v1/admin/payment-methods", admin, List.of(Map.of("code", "ONLINE", "enabled", true)), null);
    }
    assertThat(getList("/api/v1/payment-methods", token(freshEmail()))).extracting(m -> m.get("code")).contains("ONLINE");
  }

  @Test
  void unknownMethodCodeIsRejected() {
    var res = call(HttpMethod.PUT, "/api/v1/admin/payment-methods", adminToken(),
        List.of(Map.of("code", "BITCOIN", "enabled", true)), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
