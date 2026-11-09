package com.vijay.mall.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.notification.domain.Rule;
import com.vijay.mall.notification.domain.Segment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SegmentSqlTest {
  private static final Instant NOW = Instant.parse("2027-01-31T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000009");

  private static SegmentSql.Compiled one(String json) { return compile("ALL", json); }

  private static SegmentSql.Compiled compile(String match, String... rules) {
    return SegmentSql.compile(new Segment(match, Arrays.stream(rules).map(r -> JSON.readValue(r, Rule.class)).toList()), NOW);
  }

  private static Timestamp daysAgo(int d) { return Timestamp.from(NOW.minusSeconds(d * 86400L)); }

  @Test
  void orderCountIsACorrelatedCountWithABoundValue() {
    var c = one("{\"type\":\"ORDER_COUNT\",\"op\":\"EQ\",\"value\":0}");
    assertThat(c.where()).contains("select count(*) from").contains("p.user_id = u.id").contains(") = :r0v").doesNotContain("p.at >=");
    assertThat(c.params()).containsEntry("r0v", 0L).hasSize(1);
    var within = one("{\"type\":\"ORDER_COUNT\",\"op\":\"GTE\",\"value\":3,\"withinDays\":30}");
    assertThat(within.where()).contains("p.at >= :r0since").contains(") >= :r0v");
    assertThat(within.params()).containsEntry("r0since", daysAgo(30));
    assertThat(one("{\"type\":\"ORDER_COUNT\",\"op\":\"LTE\",\"value\":3}").where()).contains(") <= :r0v");
  }

  @Test
  void aPurchaseExcludesPendingAndCancelledOnlineOrdersAndIncludesCounterSalesBilledToTheCustomer() {
    String where = one("{\"type\":\"ORDER_COUNT\",\"op\":\"GTE\",\"value\":1}").where();
    assertThat(where).contains("o.status not in ('PAYMENT_PENDING', 'CANCELLED')").contains("s.customer_id is not null").contains("s.status = 'COMPLETED'");
  }

  @Test
  void totalSpentFlavours() {
    var gte = one("{\"type\":\"TOTAL_SPENT\",\"op\":\"GTE\",\"paise\":500000,\"withinDays\":7}");
    assertThat(gte.where()).contains("coalesce(sum(p.total), 0)").contains(") >= :r0p").contains("p.at >= :r0since");
    assertThat(gte.params()).containsEntry("r0p", 500000L);
    var between = one("{\"type\":\"TOTAL_SPENT\",\"op\":\"BETWEEN\",\"minPaise\":10,\"maxPaise\":20}");
    assertThat(between.where()).contains("between :r0lo and :r0hi");
    assertThat(between.params()).containsEntry("r0lo", 10L).containsEntry("r0hi", 20L);
  }

  @Test
  void averageOrderValue() {
    var c = one("{\"type\":\"AVG_ORDER_VALUE\",\"op\":\"LTE\",\"paise\":9000}");
    assertThat(c.where()).contains("select avg(p.total)").contains(") <= :r0p");
    assertThat(c.params()).containsEntry("r0p", 9000L);
  }

  @Test
  void lastOrderRecentAndLapsed() {
    var recent = one("{\"type\":\"LAST_ORDER\",\"withinDays\":5}");
    assertThat(recent.where()).contains("exists (select 1 from").contains("p.at >= :r0since").doesNotContain("not exists");
    assertThat(recent.params()).containsEntry("r0since", daysAgo(5));
    var lapsed = one("{\"type\":\"LAST_ORDER\",\"notWithinDays\":30}");
    assertThat(lapsed.where()).contains("and not exists (select 1 from").contains("p.at >= :r0since");
    assertThat(lapsed.params()).containsEntry("r0since", daysAgo(30));
  }

  @Test
  void firstOrderWithin() {
    var c = one("{\"type\":\"FIRST_ORDER_WITHIN\",\"days\":7}");
    assertThat(c.where()).contains("select min(p.at)").contains(">= :r0c");
    assertThat(c.params()).containsEntry("r0c", daysAgo(7));
  }

  @Test
  void productAndCategoryPurchasesLookAtOnlineAndCounterLines() {
    var p = one("{\"type\":\"BOUGHT_PRODUCT\",\"productId\":\"" + ID + "\",\"withinDays\":9}");
    assertThat(p.where()).contains("from order_item i join orders o").contains("from pos_sale_item i join pos_sale s").contains("i.product_id = :r0p")
        .contains("o.placed_at >= :r0since").contains("s.created_at >= :r0since");
    assertThat(p.params()).containsEntry("r0p", ID).containsEntry("r0since", daysAgo(9));
    var c = one("{\"type\":\"BOUGHT_CATEGORY\",\"categoryId\":\"" + ID + "\"}");
    assertThat(c.where()).contains("select pr.id from product pr where pr.category_id = :r0g").doesNotContain("since");
    assertThat(c.params()).containsEntry("r0g", ID);
  }

  @Test
  void couponUseWithAndWithoutACode() {
    var any = one("{\"type\":\"USED_COUPON\"}");
    assertThat(any.where()).contains("o.coupon_code is not null").contains("s.coupon_code is not null").doesNotContain("upper(");
    assertThat(any.params()).isEmpty();
    var code = one("{\"type\":\"USED_COUPON\",\"code\":\"WELCOME10\",\"withinDays\":3}");
    assertThat(code.where()).contains("upper(o.coupon_code) = :r0code").contains("upper(s.coupon_code) = :r0code");
    assertThat(code.params()).containsEntry("r0code", "WELCOME10").containsEntry("r0since", daysAgo(3));
  }

  @Test
  void registeredWithinAndBefore() {
    var within = one("{\"type\":\"REGISTERED\",\"withinDays\":7}");
    assertThat(within.where()).contains("u.created_at >= :r0c");
    assertThat(within.params()).containsEntry("r0c", daysAgo(7));
    var before = one("{\"type\":\"REGISTERED\",\"beforeDays\":90}");
    assertThat(before.where()).contains("u.created_at < :r0c");
    assertThat(before.params()).containsEntry("r0c", daysAgo(90));
  }

  @Test
  void coinBalanceAndCreditOwed() {
    var coins = one("{\"type\":\"COIN_BALANCE\",\"op\":\"GTE\",\"value\":500}");
    assertThat(coins.where()).contains("from wallet_balance w").contains(") >= :r0v");
    assertThat(coins.params()).containsEntry("r0v", 500L);
    var credit = one("{\"type\":\"CREDIT_OWED\",\"op\":\"EQ\",\"paise\":0}");
    assertThat(credit.where()).contains("from credit_account a").contains(") = :r0p");
    assertThat(credit.params()).containsEntry("r0p", 0L);
  }

  @Test
  void abandonedCartIsOldCartItemsWithNoPurchaseSince() {
    var c = one("{\"type\":\"CART_ABANDONED\",\"olderThanHours\":24}");
    assertThat(c.where()).contains("max(ci.updated_at)").contains("< :r0c").contains("not exists");
    assertThat(c.params()).containsEntry("r0c", Timestamp.from(NOW.minusSeconds(24 * 3600L)));
  }

  @Test
  void wishlistReviewPincodeSourceAndDevice() {
    var w = one("{\"type\":\"WISHLIST_HAS\",\"productId\":\"" + ID + "\"}");
    assertThat(w.where()).contains("from wishlist_item w").contains("w.product_id = :r0p");
    assertThat(w.params()).containsEntry("r0p", ID);
    assertThat(one("{\"type\":\"HAS_REVIEWED\"}").where()).contains("from product_review v");
    var pins = one("{\"type\":\"PINCODE_IN\",\"pincodes\":[\"380001\",\"380002\"]}");
    assertThat(pins.where()).contains("a.pincode in (:r0pins)");
    assertThat(pins.params()).containsEntry("r0pins", List.of("380001", "380002"));
    var source = one("{\"type\":\"SOURCE\",\"source\":\"POS\"}");
    assertThat(source.where()).contains("u.source = :r0s");
    assertThat(source.params()).containsEntry("r0s", "POS");
    assertThat(one("{\"type\":\"HAS_DEVICE\",\"has\":true}").where()).contains("exists (select 1 from device_token d").doesNotContain("not exists");
    assertThat(one("{\"type\":\"HAS_DEVICE\",\"has\":false}").where()).contains("not exists (select 1 from device_token d");
  }

  @Test
  void matchAnyOrsAllAnds() {
    String a = "{\"type\":\"HAS_REVIEWED\"}";
    String b = "{\"type\":\"HAS_DEVICE\",\"has\":true}";
    assertThat(compile("ALL", a, b).where()).contains(" and ").doesNotContain(" or ");
    assertThat(compile("ANY", a, b).where()).contains(" or ");
  }

  @Test
  void negateTurnsTheConditionAroundAndUnknownIsNeverNull() {
    var c = one("{\"type\":\"HAS_REVIEWED\",\"negate\":true}");
    assertThat(c.where()).startsWith("(not coalesce((").contains("), false)");
    assertThat(one("{\"type\":\"HAS_REVIEWED\"}").where()).startsWith("(coalesce((");
  }

  @Test
  void eachConditionGetsItsOwnParameterNames() {
    var c = compile("ALL", "{\"type\":\"ORDER_COUNT\",\"op\":\"GTE\",\"value\":1}", "{\"type\":\"ORDER_COUNT\",\"op\":\"LTE\",\"value\":9}");
    assertThat(c.params()).containsEntry("r0v", 1L).containsEntry("r1v", 9L);
  }

  @Test
  void nothingTheAdminTypesEndsUpInTheSqlText() {
    var c = one("{\"type\":\"USED_COUPON\",\"code\":\"X'; DROP TABLE users;--\"}");
    assertThat(c.where()).doesNotContain("DROP").doesNotContain("X'");
    assertThat(c.params()).containsEntry("r0code", "X'; DROP TABLE users;--");
  }

  @Test
  void anUnvalidatedTypeIsRefused() {
    assertThatThrownBy(() -> one("{\"type\":\"DROP\"}")).isInstanceOf(IllegalArgumentException.class);
  }
}
