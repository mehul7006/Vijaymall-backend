package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Real HTTP: each mistake in a request names its field and says what it must be, so the app can show it under the input. */
@SuppressWarnings({"rawtypes", "unchecked"})
class RequestErrorsIT extends IntegrationTestBase {
  private String t;
  private Object chips;

  @BeforeEach
  void setUp() {
    restock("Potato Chips", 30);
    t = token(freshEmail());
    chips = productByName(t, "Potato Chips").get("id");
  }

  private Map<String, Object> order(Map<String, Object> extra) {
    Map<String, Object> m = new HashMap<>(Map.of("items", List.of(Map.of("productId", chips, "qty", 1)), "coinMode", "NONE",
        "fulfilment", "PICKUP", "paymentMethod", "COD", "expectedTotalPaise", 1L));
    m.putAll(extra);
    return m;
  }

  private ResponseEntity<Map> place(Map<String, Object> body) {
    return call(HttpMethod.POST, "/api/v1/orders", t, body, UUID.randomUUID().toString());
  }

  private static Map<String, Object> fields(ResponseEntity<Map> r) {
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    return (Map<String, Object>) ((Map<String, Object>) r.getBody().get("details")).get("fields");
  }

  @Test
  void priceCheckIsSkippedWithoutExpectedTotalAndKeptWhenGiven() {
    Map<String, Object> noTotal = order(Map.of());
    noTotal.remove("expectedTotalPaise");
    var ok = place(noTotal);
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var changed = place(order(Map.of("expectedTotalPaise", 1L)));
    assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(changed.getBody().get("code")).isEqualTo("PRICE_CHANGED");
  }

  @Test
  void badEnumValuesListTheRealChoices() {
    assertThat(fields(place(order(Map.of("fulfilment", "XYZ"))))).isEqualTo(Map.of("fulfilment", "must be DELIVERY or PICKUP"));
    assertThat(fields(place(order(Map.of("coinMode", "x"))))).isEqualTo(Map.of("coinMode", "must be NONE, ALL or PERCENT"));
  }

  @Test
  void unknownPaymentMethodEchoesWhatWasSent() {
    assertThat(fields(place(order(Map.of("paymentMethod", "CASH")))).get("paymentMethod")).isEqualTo("must be COD or ONLINE (got \"CASH\")");
  }

  @Test
  void malformedAndEmptyBodiesAreNotJson() {
    for (Object body : new Object[] {"{not json", "[1]"}) {
      var r = call(HttpMethod.POST, "/api/v1/orders", t, body, UUID.randomUUID().toString());
      assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(r.getBody().get("message")).isEqualTo("The request body is not valid JSON");
      assertThat((Map) r.getBody().get("details")).isEmpty();
    }
    var empty = client().post().uri("/api/v1/orders").headers(h -> { h.setBearerAuth(t); h.set("Idempotency-Key", "k"); })
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).retrieve().toEntity(Map.class);
    assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(empty.getBody().get("message")).isEqualTo("The request body is not valid JSON");
  }

  @Test
  void missingIdempotencyKeyNamesTheHeader() {
    var r = call(HttpMethod.POST, "/api/v1/orders", t, order(Map.of()), null);
    assertThat(r.getBody().get("message")).isEqualTo("Missing header Idempotency-Key");
    assertThat(fields(r)).isEqualTo(Map.of("Idempotency-Key", "is required"));
  }

  @Test
  void badLineValuesUseTheJsonPath() {
    var qty = fields(place(order(Map.of("items", List.of(Map.of("productId", chips, "qty", "two"))))));
    assertThat(qty).isEqualTo(Map.of("items[0].qty", "must be a whole number"));
    var id = fields(place(order(Map.of("items", List.of(Map.of("productId", "abc", "qty", 1))))));
    assertThat(id).isEqualTo(Map.of("items[0].productId", "must be a valid id"));
  }

  @Test
  void quantityRuleAndCartQuoteRulesStillWork() {
    var zero = post("/api/v1/cart/quote", t, Map.of("items", List.of(Map.of("productId", chips, "qty", 0))));
    assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(zero.getBody().toString()).contains("quantity must be 1 to 99");
  }

  @Test
  void emptyCouponBodyReportsEveryRequiredFieldAtOnce() {
    var f = fields(post("/api/v1/admin/coupons", adminToken(), Map.of()));
    assertThat(f).containsEntry("code", "is required").containsEntry("kind", "is required").containsEntry("value", "is required");
  }

  @Test
  void emptyProductBodyAndNegativePriceAreReportedPerField() {
    var empty = fields(post("/api/v1/admin/products", adminToken(), Map.of()));
    assertThat(empty).containsEntry("nameEn", "is required").containsEntry("categoryId", "is required")
        .containsEntry("mrpPaise", "is required").containsEntry("pricePaise", "is required");
    var neg = fields(post("/api/v1/admin/products", adminToken(), Map.of("pricePaise", -5)));
    assertThat(neg).containsEntry("pricePaise", "must be 0 or more");
  }

  @Test
  void unknownOrderStatusListsTheRealStatuses() {
    var r = post("/api/v1/admin/orders/" + UUID.randomUUID() + "/status", adminToken(), Map.of("status", "NOPE"));
    assertThat(fields(r).get("status")).isEqualTo("must be one of PAYMENT_PENDING, PLACED, CONFIRMED, PACKED, OUT_FOR_DELIVERY, READY_FOR_PICKUP, DELIVERED, PICKED_UP, CANCELLED");
  }

  @Test
  void badIdInThePathNamesTheParameter() {
    assertThat(fields(get("/api/v1/admin/orders/not-a-uuid", adminToken()))).isEqualTo(Map.of("id", "must be a valid id"));
  }

  @Test
  void missingOrBadQueryParameterNamesTheParameter() {
    assertThat(fields(get("/api/v1/admin/notifications/audience-size", adminToken()))).isEqualTo(Map.of("audience", "is required"));
    assertThat(fields(get("/api/v1/admin/notifications/audience-size?audience=NOBODY", adminToken())).get("audience").toString())
        .startsWith("must be ");
  }

  @Test
  void addressPincodeMustBeSixDigits() {
    Map<String, Object> a = new HashMap<>(Map.of("label", "Home", "line1", "1 Road", "area", "A", "pincode", "12"));
    assertThat(fields(post("/api/v1/me/addresses", t, a)).get("pincode")).isEqualTo("must be 6 digits");
    a.put("pincode", "39000a");
    assertThat(fields(post("/api/v1/me/addresses", t, a)).get("pincode")).isEqualTo("must be 6 digits");
    a.put("pincode", "390001");
    var id = (String) post("/api/v1/me/addresses", t, a).getBody().get("id");
    a.put("pincode", "1234567");
    assertThat(fields(call(HttpMethod.PUT, "/api/v1/me/addresses/" + id, t, a, null)).get("pincode")).isEqualTo("must be 6 digits");
  }
}
