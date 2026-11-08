package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** The metrics endpoint Prometheus scrapes, on its own private port, with technical and business numbers in it. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ObservabilityIT extends IntegrationTestBase {

  private String scrape() {
    return RestClient.builder().baseUrl("http://localhost:" + managementPort).defaultStatusHandler(_ -> true, (_, _) -> { }).build()
        .get().uri("/actuator/prometheus").retrieve().body(String.class);
  }

  @Test
  void prometheusSeesTheHttpJvmDatabaseAndBusinessNumbers() {
    restock("Basmati Rice", 50);
    String admin = adminToken();
    String cust = token(freshEmail());
    Object pid = productByName(cust, "Basmati Rice").get("id");
    var items = List.of(Map.of("productId", pid, "qty", 1));
    long total = ((Number) post("/api/v1/cart/quote", cust, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP")).getBody()
        .get("totalPaise")).longValue();
    var order = call(HttpMethod.POST, "/api/v1/orders", cust, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP",
        "paymentMethod", "COD", "expectedTotalPaise", total), UUID.randomUUID().toString());
    assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var sale = call(HttpMethod.POST, "/api/v1/pos/sales", admin, Map.of("cart", Map.of("items", items),
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", total))), UUID.randomUUID().toString());
    assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String text = scrape();
    assertThat(text).contains("http_server_requests_seconds_count").contains("application=\"vijay-mall\"")
        .contains("http_server_requests_seconds_bucket").contains("jvm_memory_used_bytes").contains("hikaricp_connections_active")
        .contains("mall_orders_placed_total{").contains("mall_orders_value_paise_total{").contains("mall_pos_sales_total{")
        .contains("mall_pos_sales_value_paise_total{").contains("mall_order_place_seconds_count{").contains("mall_pos_sale_seconds_count{")
        .contains("mall_audit_actions_total{").contains("mall_refunds_failed").contains("mall_refunds_pending").contains("mall_outbox_pending")
        .contains("mall_stock_low_products").contains("mall_orders_by_status{").contains("mall_config_issues").contains("mall_cache_entries");
    assertThat(text).as("no personal data in labels").doesNotContain("@example.com").doesNotContain("9000000001");
  }

  @Test
  void metricsAndHealthAreOnThePrivatePortNotThePublicOne() {
    var health = RestClient.builder().baseUrl("http://localhost:" + managementPort).defaultStatusHandler(_ -> true, (_, _) -> { }).build()
        .get().uri("/actuator/health").retrieve().toEntity(String.class);
    assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(health.getBody()).contains("UP");
    var publicPort = get("/actuator/prometheus", null);
    assertThat(publicPort.getStatusCode().value()).as("not served on the public port").isIn(401, 403, 404);
  }

  @Test
  void everyApiAnswerCarriesATraceIdForTheLogs() {
    var res = client().get().uri("/api/v1/categories").retrieve().toEntity(String.class);
    assertThat(res.getHeaders().getFirst("X-Trace-Id")).hasSize(8);
  }
}
