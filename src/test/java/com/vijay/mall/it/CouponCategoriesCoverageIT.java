package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Coupons limited to several categories: stored in the link table, replaced on update, first category kept first. */
@SuppressWarnings({"rawtypes", "unchecked"})
class CouponCategoriesCoverageIT extends IntegrationTestBase {

  private String code() { return "CC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }

  /** Three real category ids in ascending order. */
  private List<String> threeCategories() {
    List<String> ids = new ArrayList<>(jdbc.queryForList("select id::text from category order by id limit 3", String.class));
    assertThat(ids).hasSizeGreaterThanOrEqualTo(3);
    return ids;
  }

  private Map<String, Object> body(String code, Object categoryIds) {
    Map<String, Object> b = new HashMap<>(Map.of("code", code, "kind", "FLAT", "value", 500));
    if (categoryIds != null) b.put("categoryIds", categoryIds);
    return b;
  }

  private int linkRows(String couponId) {
    return jdbc.queryForObject("select count(*) from coupon_category where coupon_id = ?::uuid", Integer.class, couponId);
  }

  @Test
  void severalCategoriesAreStoredKeepTheirFirstAndAreReplacedOnUpdate() {
    String admin = adminToken();
    List<String> sorted = threeCategories();
    String low = sorted.get(0), mid = sorted.get(1), high = sorted.get(2);
    String c = code();

    // the first one given is the highest id, so it must stay first although the rows sort ascending
    var created = post("/api/v1/admin/coupons", admin, body(c, List.of(high, low, mid)));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) created.getBody().get("id");
    assertThat((List<String>) created.getBody().get("categoryIds")).containsExactly(high, low, mid);
    assertThat(created.getBody().get("categoryId")).isEqualTo(high);
    assertThat(linkRows(id)).isEqualTo(3);

    // it shows in the public list with the same categories
    var offered = getList("/api/v1/coupons", null).stream().filter(x -> c.equals(x.get("code"))).findFirst().orElseThrow();
    assertThat((List<String>) offered.get("categoryIds")).containsExactly(high, low, mid);

    // an update replaces the links, it does not add to them
    var updated = call(HttpMethod.PUT, "/api/v1/admin/coupons/" + id, admin, body(c, List.of(mid)), null);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List<String>) updated.getBody().get("categoryIds")).containsExactly(mid);
    assertThat(linkRows(id)).isEqualTo(1);

    // an empty list means every category
    var all = call(HttpMethod.PUT, "/api/v1/admin/coupons/" + id, admin, body(c, List.of()), null);
    assertThat((List<String>) all.getBody().get("categoryIds")).isEmpty();
    assertThat(all.getBody().get("categoryId")).isNull();
    assertThat(linkRows(id)).isZero();

    // an unknown category is rejected as a bad reference and leaves no half-written coupon behind
    String ghost = code();
    var bad = post("/api/v1/admin/coupons", admin, body(ghost, List.of(low, UUID.randomUUID().toString())));
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(getList("/api/v1/admin/coupons", admin)).noneMatch(x -> ghost.equals(x.get("code")));

    // deleting removes the links with the coupon
    var again = call(HttpMethod.PUT, "/api/v1/admin/coupons/" + id, admin, body(c, List.of(low, high)), null);
    assertThat((List<String>) again.getBody().get("categoryIds")).containsExactly(low, high);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/coupons/" + id, admin, null, null).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(linkRows(id)).isZero();
  }

  @Test
  void onlyLinesOfTheCouponsCategoriesAreDiscounted() {
    String admin = adminToken();
    Object juice = productByName(admin, "Orange Juice").get("id");
    String juiceCategory = jdbc.queryForObject("select category_id::text from product where id = ?::uuid", String.class, juice.toString());
    String other = threeCategories().stream().filter(x -> !x.equals(juiceCategory)).findFirst().orElseThrow();
    String c = code();
    String id = (String) post("/api/v1/admin/coupons", admin, body(c, List.of(juiceCategory, other))).getBody().get("id");
    try {
      var quote = post("/api/v1/cart/quote", admin, Map.of("items", List.of(Map.of("productId", juice, "qty", 1)),
          "couponCode", c, "coinMode", "NONE", "fulfilment", "PICKUP"));
      assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(((Number) quote.getBody().get("couponDiscountPaise")).longValue()).isPositive();
      List<Map> perCategory = (List<Map>) quote.getBody().get("couponByCategory");
      assertThat(perCategory).anyMatch(x -> juiceCategory.equals(x.get("categoryId"))
          && ((Number) x.get("discountPaise")).longValue() > 0);
    } finally {
      call(HttpMethod.DELETE, "/api/v1/admin/coupons/" + id, admin, null, null);
    }
  }
}
