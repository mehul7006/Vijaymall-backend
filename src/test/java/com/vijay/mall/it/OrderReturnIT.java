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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Returns on delivered online orders and the admin's refund monitor, against real PostgreSQL with a fake payment provider. */
@Import(OrderReturnIT.FakeProvider.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class OrderReturnIT extends IntegrationTestBase {

  @TestConfiguration
  static class FakeProvider {
    @Bean FakeGateway fakeGateway() { return new FakeGateway(); }
  }

  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry r) { r.add("app.payment.online-provider", () -> "fake"); }

  @Autowired FakeGateway fake;

  @BeforeEach
  void ready() {
    restock("Orange Juice", 40);
    call(HttpMethod.PUT, "/api/v1/admin/payment-methods", adminToken(), List.of(Map.of("code", "ONLINE", "enabled", true)), null);
  }

  private record Delivered(String token, String orderId, long total, Object productId) {}

  private int stock(Object pid) {
    return jdbc.queryForObject("select qty_on_hand from inventory where product_id = ?::uuid", Integer.class, pid.toString());
  }

  /** Places an order of {@code qty} Orange Juice, pays it when online, confirms and delivers it. */
  private Delivered delivered(String method, int qty) {
    String admin = adminToken();
    String token = token(freshEmail());
    Object pid = productByName(token, "Orange Juice").get("id");
    var items = List.of(Map.of("productId", pid, "qty", qty));
    Map<String, Object> req = Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP");
    long total = ((Number) post("/api/v1/cart/quote", token, req).getBody().get("totalPaise")).longValue();
    var res = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP",
        "paymentMethod", method, "expectedTotalPaise", total), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) res.getBody().get("id");
    if ("ONLINE".equals(method)) {
      var hook = client().post().uri("/api/v1/payments/webhook/fake").contentType(MediaType.APPLICATION_JSON)
          .headers(h -> h.set("x-fake-signature", "ok"))
          .body(Map.of("eventId", "ev-" + UUID.randomUUID(), "type", "captured", "providerOrderId", "fake_" + id, "amount", total))
          .retrieve().toEntity(Map.class);
      assertThat(hook.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    for (String s : List.of("CONFIRMED", "PICKED_UP")) {
      assertThat(post("/api/v1/admin/orders/" + id + "/status", admin, Map.of("status", s)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    return new Delivered(token, id, total, pid);
  }

  private ResponseEntity<Map> giveBack(String token, Delivered d, int qty, boolean restock, String key) {
    return call(HttpMethod.POST, "/api/v1/admin/orders/" + d.orderId() + "/returns", token,
        Map.of("reason", "damaged on arrival", "items", List.of(Map.of("productId", d.productId(), "qty", qty, "restock", restock))), key);
  }

  @Test
  void aCashOnDeliveryReturnRefundsTheShareRestocksAndCannotGoOverWhatWasSold() {
    String admin = adminToken();
    var d = delivered("COD", 3);
    int afterSale = stock(d.productId());

    String key = UUID.randomUUID().toString();
    var one = giveBack(admin, d, 1, true, key);
    assertThat(one.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(((Number) one.getBody().get("refundPaise")).longValue()).isEqualTo(d.total() / 3);
    assertThat(one.getBody().get("refundMode")).as("paid in cash at the door, so the shop hands it back").isEqualTo("CASH");
    assertThat(stock(d.productId())).isEqualTo(afterSale + 1);

    var again = giveBack(admin, d, 1, true, key);
    assertThat(again.getBody().get("id")).as("same key, same return").isEqualTo(one.getBody().get("id"));
    assertThat(stock(d.productId())).as("not restocked twice").isEqualTo(afterSale + 1);

    var rest = giveBack(admin, d, 2, false, UUID.randomUUID().toString());
    assertThat(((Number) rest.getBody().get("refundPaise")).longValue()).as("all three add up to the whole line").isEqualTo(d.total() - d.total() / 3);
    assertThat(stock(d.productId())).as("not resellable, so not restocked").isEqualTo(afterSale + 1);

    var tooMany = giveBack(admin, d, 1, true, UUID.randomUUID().toString());
    assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(tooMany.getBody().get("code")).isEqualTo("RETURN_EXCEEDS_SOLD");

    var mine = (List<Map>) get("/api/v1/orders/" + d.orderId() + "/returns", d.token()).getBody().get("_array");
    assertThat(mine).hasSize(2);
    assertThat(get("/api/v1/orders/" + d.orderId() + "/returns", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var staff = (List<Map>) get("/api/v1/admin/orders/" + d.orderId() + "/returns", admin).getBody().get("_array");
    assertThat(staff.get(0).get("items")).isNotNull();
  }

  @Test
  void anOnlineReturnSendsThePartialRefundBackThroughThePaymentProvider() {
    String admin = adminToken();
    var d = delivered("ONLINE", 2);
    int refundsBefore = fake.refunds.size();

    var res = giveBack(admin, d, 1, true, UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(res.getBody().get("refundMode")).isEqualTo("ONLINE");
    long half = ((Number) res.getBody().get("refundPaise")).longValue();
    assertThat(half).isEqualTo(d.total() / 2);
    assertThat(fake.refunds).hasSize(refundsBefore + 1);
    assertThat(fake.refunds.get(fake.refunds.size() - 1)).endsWith(":" + half);

    var issued = (List<Map>) get("/api/v1/admin/refunds?status=ISSUED&size=50", admin).getBody().get("items");
    assertThat(issued).anySatisfy(r -> assertThat(((Number) r.get("amountPaise")).longValue()).isEqualTo(half));

    var second = giveBack(admin, d, 1, true, UUID.randomUUID().toString());
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(fake.refunds).as("the rest goes back too, never more than was paid").hasSize(refundsBefore + 2);
  }

  @Test
  void returnsNeedADeliveredOrderRealProductsAndStaff() {
    String admin = adminToken();
    var d = delivered("COD", 1);
    String customer = d.token();
    assertThat(call(HttpMethod.POST, "/api/v1/admin/orders/" + d.orderId() + "/returns", customer,
        Map.of("reason", "x", "items", List.of(Map.of("productId", d.productId(), "qty", 1))), UUID.randomUUID().toString())
        .getStatusCode()).as("customers cannot record their own refund").isEqualTo(HttpStatus.FORBIDDEN);

    assertThat(giveBack(admin, new Delivered(d.token(), d.orderId(), d.total(), UUID.randomUUID()), 1, true, UUID.randomUUID().toString())
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/orders/" + d.orderId() + "/returns", admin,
        Map.of("reason", " ", "items", List.of(Map.of("productId", d.productId(), "qty", 1))), UUID.randomUUID().toString())
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, "/api/v1/admin/orders/" + d.orderId() + "/returns", admin,
        Map.of("reason", "x", "items", List.of(Map.of("productId", d.productId(), "qty", 0))), UUID.randomUUID().toString())
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    String cust = token(freshEmail());
    Object pid = productByName(cust, "Orange Juice").get("id");
    var items = List.of(Map.of("productId", pid, "qty", 1));
    long total = ((Number) post("/api/v1/cart/quote", cust, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP")).getBody()
        .get("totalPaise")).longValue();
    String open = (String) call(HttpMethod.POST, "/api/v1/orders", cust, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP",
        "paymentMethod", "COD", "expectedTotalPaise", total), UUID.randomUUID().toString()).getBody().get("id");
    var notYet = giveBack(admin, new Delivered(cust, open, total, pid), 1, true, UUID.randomUUID().toString());
    assertThat(notYet.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(notYet.getBody().get("code")).isEqualTo("ORDER_NOT_DELIVERED");
    assertThat(giveBack(admin, new Delivered(cust, UUID.randomUUID().toString(), 0, pid), 1, true, UUID.randomUUID().toString())
        .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void theRefundMonitorListsRefundsAndRetriesOnlyFailedOnes() {
    String admin = adminToken();
    var d = delivered("ONLINE", 1);
    giveBack(admin, d, 1, true, UUID.randomUUID().toString());

    assertThat(get("/api/v1/admin/refunds?status=NOPE", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/refunds", token("cashier@vijaysupermall.local")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    var all = (List<Map>) get("/api/v1/admin/refunds?size=50", admin).getBody().get("items");
    Map mine = all.stream().filter(r -> d.orderId().equals(r.get("orderId"))).findFirst().orElseThrow();
    String id = (String) mine.get("id");
    assertThat(post("/api/v1/admin/refunds/" + id + "/retry", admin, null).getStatusCode()).as("only FAILED can be retried")
        .isEqualTo(HttpStatus.CONFLICT);

    jdbc.update("update refund set status = 'FAILED', attempts = 10, last_error = 'provider down' where id = ?::uuid", id);
    var failed = (List<Map>) get("/api/v1/admin/refunds?status=FAILED&size=50", admin).getBody().get("items");
    assertThat(failed).anySatisfy(r -> assertThat(r.get("lastError")).isEqualTo("provider down"));
    assertThat(post("/api/v1/admin/refunds/" + id + "/retry", admin, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    String status = jdbc.queryForObject("select status from refund where id = ?::uuid", String.class, id);
    assertThat(status).as("sent again straight away").isEqualTo("ISSUED");
  }
}
