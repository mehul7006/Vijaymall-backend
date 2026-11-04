package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The saved basket (GET / PUT / DELETE /api/v1/cart), end to end. */
@SuppressWarnings({"rawtypes", "unchecked"})
class CartBasketIT extends IntegrationTestBase {

  private List<String> twoProductIds(String token) {
    var items = (List<Map<String, Object>>) get("/api/v1/products?size=50", token).getBody().get("items");
    return items.stream().map(p -> (String) p.get("id")).distinct().limit(2).toList();
  }

  private Map<String, Object> basket(List<String> ids, int qty) {
    return Map.of("items", ids.stream().map(id -> Map.of("productId", id, "qty", qty)).toList(), "couponCode", " fest50 ",
        "coinMode", "PERCENT", "percent", 40);
  }

  // ---------- saved basket ----------
  @Test
  void aNewCustomerGetsAnEmptyBasketNotA404() {
    var res = get("/api/v1/cart", token(freshEmail()));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List) res.getBody().get("items")).isEmpty();
    assertThat(res.getBody()).containsEntry("coinMode", "NONE").containsEntry("couponCode", null);
  }

  @Test
  void basketFollowsTheCustomerToAnotherDevice() {
    String email = freshEmail();
    String deviceA = token(email);
    var ids = twoProductIds(deviceA);
    var put = call(HttpMethod.PUT, "/api/v1/cart", deviceA, basket(ids, 2), null);
    assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

    String deviceB = token(email); // a second sign-in, as on another device
    var got = get("/api/v1/cart", deviceB);
    assertThat(got.getBody()).isEqualTo(put.getBody());
    var items = (List<Map<String, Object>>) got.getBody().get("items");
    assertThat(items).extracting(i -> i.get("productId")).containsExactlyElementsOf(ids);
    assertThat(items).allMatch(i -> ((Number) i.get("qty")).intValue() == 2);
    assertThat(got.getBody()).containsEntry("couponCode", "FEST50").containsEntry("coinMode", "PERCENT").containsEntry("percent", 40);
  }

  @Test
  void putReplacesTheWholeBasketAndMergesRepeatedLines() {
    String t = token(freshEmail());
    var ids = twoProductIds(t);
    call(HttpMethod.PUT, "/api/v1/cart", t, basket(ids, 1), null);
    var again = call(HttpMethod.PUT, "/api/v1/cart", t, Map.of("items", List.of(
        Map.of("productId", ids.get(0), "qty", 1), Map.of("productId", ids.get(0), "qty", 2))), null);
    var items = (List<Map<String, Object>>) again.getBody().get("items");
    assertThat(items).hasSize(1);
    assertThat(((Number) items.get(0).get("qty")).intValue()).isEqualTo(3);
    assertThat(again.getBody()).containsEntry("couponCode", null).containsEntry("coinMode", "NONE").containsEntry("percent", null);
  }

  @Test
  void unknownProductsAreSkippedAndBadInputIsRejected() {
    String t = token(freshEmail());
    var ids = twoProductIds(t);
    var res = call(HttpMethod.PUT, "/api/v1/cart", t, Map.of("items", List.of(
        Map.of("productId", UUID.randomUUID().toString(), "qty", 1), Map.of("productId", ids.get(0), "qty", 1))), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List) res.getBody().get("items")).hasSize(1);
    assertThat(call(HttpMethod.PUT, "/api/v1/cart", t, Map.of("items", List.of(Map.of("productId", ids.get(0), "qty", 0))), null)
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void soldOutProductsDisappearFromTheBasketOnRead() {
    String t = token(freshEmail());
    var ids = twoProductIds(t);
    call(HttpMethod.PUT, "/api/v1/cart", t, basket(ids, 1), null);
    int before = jdbc.queryForObject("select qty_on_hand from inventory where product_id = ?::uuid", Integer.class, ids.get(1));
    try {
      jdbc.update("update inventory set qty_on_hand = 0 where product_id = ?::uuid", ids.get(1));
      var items = (List<Map<String, Object>>) get("/api/v1/cart", t).getBody().get("items");
      assertThat(items).extracting(i -> i.get("productId")).containsExactly(ids.get(0));
    } finally {
      jdbc.update("update inventory set qty_on_hand = ? where product_id = ?::uuid", before, ids.get(1));
    }
  }

  @Test
  void basketsArePerCustomerAndDeleteClearsIt() {
    String a = token(freshEmail());
    String b = token(freshEmail());
    call(HttpMethod.PUT, "/api/v1/cart", a, basket(twoProductIds(a), 1), null);
    assertThat((List) get("/api/v1/cart", b).getBody().get("items")).as("another customer").isEmpty();
    assertThat(call(HttpMethod.DELETE, "/api/v1/cart", a, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat((List) get("/api/v1/cart", a).getBody().get("items")).isEmpty();
  }

  @Test
  void basketNeedsSignIn() {
    assertThat(get("/api/v1/cart", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(call(HttpMethod.PUT, "/api/v1/cart", null, Map.of("items", List.of()), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
