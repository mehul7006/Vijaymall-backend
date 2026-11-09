package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.support.FakeGateway;
import com.vijay.mall.support.FlashTestBase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Flash sale units of an online (pay now) order come back when the payment fails or times out. */
@Import(FlashSaleOnlineIT.FakeProvider.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class FlashSaleOnlineIT extends FlashTestBase {

  @TestConfiguration
  static class FakeProvider {
    @Bean FakeGateway fakeGateway() { return new FakeGateway(); }
  }

  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry r) { r.add("app.payment.online-provider", () -> "fake"); }

  @Autowired OrderService orders;

  @BeforeEach
  void enableOnlinePayment() {
    call(HttpMethod.PUT, "/api/v1/admin/payment-methods", adminToken(), List.of(Map.of("code", "ONLINE", "enabled", true)), null);
  }

  private String placeOnline(String token, Object pid, int qty) {
    long total = ((Number) quote(token, pid, qty).get("totalPaise")).longValue();
    var res = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", List.of(Map.of("productId", pid, "qty", qty)), "coinMode", "NONE",
        "fulfilment", "PICKUP", "paymentMethod", "ONLINE", "expectedTotalPaise", total), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return (String) res.getBody().get("id");
  }

  @Autowired ApplicationEventPublisher events;

  @Test
  void anUnpaidOrderThatTimesOutGivesItsSaleUnitsBack() {
    Object pid = newProduct(10000, 20).get("id");
    Map<String, Object> sale = createRunning(pid, 2, Map.of());
    Object itemId = saleItem(sale.get("id"), pid).get("id");
    String token = token(freshEmail());
    String id = placeOnline(token, pid, 2);
    assertThat(soldQty(itemId)).isEqualTo(2);
    assertThat(quote(token(freshEmail()), pid, 1).get("subtotalPaise")).as("sold out while the payment is pending").isEqualTo(10000);
    jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?::uuid", Timestamp.from(Instant.now().minusSeconds(60)), id);
    orders.expireUnpaid();
    assertThat(get("/api/v1/orders/" + id, token).getBody().get("status")).isEqualTo("CANCELLED");
    assertThat(soldQty(itemId)).isZero();
    orders.expireUnpaid();
    assertThat(soldQty(itemId)).as("only once").isZero();
    cancelSale(sale.get("id"));
  }

  @Test
  void aFailedPaymentGivesTheUnitsBack() {
    Object pid = newProduct(10000, 20).get("id");
    Map<String, Object> sale = createRunning(pid, 2, Map.of());
    Object itemId = saleItem(sale.get("id"), pid).get("id");
    String token = token(freshEmail());
    String id = placeOnline(token, pid, 1);
    assertThat(soldQty(itemId)).isEqualTo(1);
    events.publishEvent(new PaymentEvents.PaymentFailed(UUID.fromString(id), "declined"));
    assertThat(get("/api/v1/orders/" + id, token).getBody().get("status")).isEqualTo("CANCELLED");
    assertThat(soldQty(itemId)).isZero();
    cancelSale(sale.get("id"));
  }
}
