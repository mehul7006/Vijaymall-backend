package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import com.vijay.mall.wallet.app.WalletService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A return preview must show exactly what the real return that follows does: refund, coupon taken back, coins taken back and
 * given back (and the credit adjustment on a counter bill paid on credit), for online orders and counter bills, under both
 * coupon policies. Against real PostgreSQL.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ReturnPreviewParityIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";
  private static final long PAISE_PER_COIN = 100;

  @Autowired WalletService wallet;

  private UUID juice;
  private UUID rice;

  @BeforeEach
  void setUp() {
    restock("Orange Juice", 200);
    restock("Basmati Rice", 200);
    juice = jdbc.queryForObject("select id from product where name_en = 'Orange Juice' order by id limit 1", UUID.class);
    rice = jdbc.queryForObject("select id from product where name_en = 'Basmati Rice' and pack_size = '1 kg'", UUID.class);
    post("/api/v1/admin/coin-rules", adminToken(), Map.of("name", "Parity " + UUID.randomUUID(), "spendPaise", 1000, "coins", 1, "mode", "PRO_RATA"));
  }

  @AfterEach
  void defaultPolicy() { policy("KEEP"); }

  private void policy(String value) {
    var res = call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(),
        Map.of("changes", List.of(Map.of("key", "returns.couponPolicy", "value", value))), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private static long n(Object o) { return ((Number) o).longValue(); }

  private String coupon() {
    long juicePrice = jdbc.queryForObject("select price_paise from product where id = ?", Long.class, juice);
    String code = "P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    var res = post("/api/v1/admin/coupons", adminToken(), Map.of("code", code, "kind", "PERCENT", "value", 10,
        "productIds", List.of(juice), "minOrderPaise", juicePrice * 2));
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return code;
  }

  /** The figures every preview and its return must agree on. */
  private void assertSame(Map preview, Map real, boolean counter) {
    assertThat(n(preview.get("refundPaise"))).as("refund").isEqualTo(n(real.get("refundPaise")));
    assertThat(n(preview.get("couponClawbackPaise"))).as("coupon taken back").isEqualTo(n(real.get("couponClawbackPaise")));
    assertThat(n(preview.get("coinsClawedBack"))).as("coins taken back").isEqualTo(n(real.get("coinsClawedBack")));
    assertThat(n(preview.get("coinsRestored"))).as("coins given back").isEqualTo(n(real.get("coinsRestored")));
    assertThat(n(preview.get("coinsClawedBackPaise"))).isEqualTo(n(real.get("coinsClawedBack")) * PAISE_PER_COIN);
    assertThat(n(preview.get("coinsRestoredPaise"))).isEqualTo(n(real.get("coinsRestored")) * PAISE_PER_COIN);
    if (counter) {
      assertThat(n(preview.get("creditAdjustmentPaise"))).as("credit adjustment").isEqualTo(n(real.get("creditAdjustedPaise")));
      assertThat(n(preview.get("payOutPaise"))).as("paid out").isEqualTo(n(real.get("refundPaise")) - n(real.get("creditAdjustedPaise")));
    }
  }

  // ---------- counter ----------
  private ResponseEntity<Map> posReturn(String cashier, Object saleId, Object itemId, int qty, boolean preview) {
    return call(HttpMethod.POST, "/api/v1/pos/sales/" + saleId + "/returns" + (preview ? "/preview" : ""), cashier,
        Map.of("items", List.of(Map.of("saleItemId", itemId, "qty", qty)), "reason", "parity"), preview ? null : UUID.randomUUID().toString());
  }

  private void counterParity(String policy, boolean drainCoinsFirst) {
    policy(policy);
    String cashier = token(CASHIER);
    String mobile = "95" + String.format("%08d", (long) (Math.random() * 100_000_000L));
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/credit/limit", adminToken(), Map.of("mobile", mobile, "limitPaise", 500000), null)
        .getStatusCode()).isEqualTo(HttpStatus.OK);
    UUID customer = jdbc.queryForObject("select id from users where mobile = ?", UUID.class, mobile);
    wallet.credit(customer, 300, UUID.randomUUID());
    Map<String, Object> cart = new HashMap<>(Map.of("items", List.of(Map.of("productId", juice, "qty", 2), Map.of("productId", rice, "qty", 1)),
        "customerMobile", mobile, "couponCode", coupon(), "coinMode", "PERCENT", "percent", 30));
    long total = n(post("/api/v1/pos/quote", cashier, cart).getBody().get("totalPaise"));
    long credit = 10000;
    var sold = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", cart, "payments", List.of(
        Map.of("mode", "CASH", "amountPaise", total - credit), Map.of("mode", "CREDIT", "amountPaise", credit))), UUID.randomUUID().toString());
    assertThat(sold.getStatusCode()).as(String.valueOf(sold.getBody())).isEqualTo(HttpStatus.CREATED);
    Map sale = sold.getBody();
    Map j = ((List<Map>) sale.get("items")).stream().filter(i -> juice.toString().equals(i.get("productId"))).findFirst().orElseThrow();
    Map r = ((List<Map>) sale.get("items")).stream().filter(i -> rice.toString().equals(i.get("productId"))).findFirst().orElseThrow();
    if (drainCoinsFirst) wallet.redeem(customer, wallet.balance(customer), UUID.randomUUID());

    Map preview = posReturn(cashier, sale.get("id"), j.get("id"), 1, true).getBody();
    Map real = posReturn(cashier, sale.get("id"), j.get("id"), 1, false).getBody();
    assertSame(preview, real, true);
    assertThat(n(preview.get("coinsRestored"))).as("the bill spent coins").isPositive();
    assertThat(n(preview.get("creditAdjustmentPaise"))).as("part of the refund lowers the credit").isPositive();
    if (drainCoinsFirst) assertThat(n(real.get("coinsClawedBack"))).as("nothing left to take back").isZero();
    else assertThat(n(real.get("coinsClawedBack"))).as("earned coins are taken back").isPositive();

    Map secondPreview = posReturn(cashier, sale.get("id"), r.get("id"), 1, true).getBody();
    Map second = posReturn(cashier, sale.get("id"), r.get("id"), 1, false).getBody();
    assertSame(secondPreview, second, true);
    assertThat(n(preview.get("coinsRestoredPaise")) + n(secondPreview.get("coinsRestoredPaise"))).isPositive();
  }

  @Test void counterKeepPolicy() { counterParity("KEEP", false); }

  @Test void counterClawbackPolicy() { counterParity("CLAWBACK_BELOW_MINIMUM", false); }

  @Test void counterWhenTheCustomerHasAlreadySpentTheEarnedCoins() { counterParity("KEEP", true); }

  @Test void counterBillWithoutACustomerMovesNoCoinsAndNoCredit() {
    policy("KEEP");
    String cashier = token(CASHIER);
    Map cart = Map.of("items", List.of(Map.of("productId", rice, "qty", 2)));
    long total = n(post("/api/v1/pos/quote", cashier, cart).getBody().get("totalPaise"));
    Map sale = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", cart,
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", total))), UUID.randomUUID().toString()).getBody();
    Object item = ((List<Map>) sale.get("items")).get(0).get("id");
    Map preview = posReturn(cashier, sale.get("id"), item, 1, true).getBody();
    Map real = posReturn(cashier, sale.get("id"), item, 1, false).getBody();
    assertSame(preview, real, true);
    assertThat(n(preview.get("coinsClawedBack")) + n(preview.get("coinsRestored")) + n(preview.get("creditAdjustmentPaise"))).isZero();
    assertThat(n(preview.get("payOutPaise"))).isEqualTo(n(preview.get("refundPaise")));
  }

  // ---------- online ----------
  private ResponseEntity<Map> orderReturn(String orderId, UUID product, int qty, boolean preview) {
    return call(HttpMethod.POST, "/api/v1/admin/orders/" + orderId + "/returns" + (preview ? "/preview" : ""), adminToken(),
        Map.of("reason", "damaged", "items", List.of(Map.of("productId", product, "qty", qty))), preview ? null : UUID.randomUUID().toString());
  }

  private void onlineParity(String policy, boolean drainCoinsFirst) {
    policy(policy);
    String email = freshEmail();
    String token = token(email);
    UUID customer = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    wallet.credit(customer, 300, UUID.randomUUID());
    Map<String, Object> req = new HashMap<>(Map.of("items", List.of(Map.of("productId", juice, "qty", 2), Map.of("productId", rice, "qty", 1)),
        "coinMode", "PERCENT", "percent", 30, "fulfilment", "PICKUP", "couponCode", coupon()));
    long total = n(post("/api/v1/cart/quote", token, req).getBody().get("totalPaise"));
    Map<String, Object> order = new HashMap<>(req);
    order.put("paymentMethod", "COD");
    order.put("expectedTotalPaise", total);
    var placed = call(HttpMethod.POST, "/api/v1/orders", token, order, UUID.randomUUID().toString());
    assertThat(placed.getStatusCode()).as(String.valueOf(placed.getBody())).isEqualTo(HttpStatus.CREATED);
    String id = (String) placed.getBody().get("id");
    for (String s : List.of("CONFIRMED", "PICKED_UP")) {
      assertThat(post("/api/v1/admin/orders/" + id + "/status", adminToken(), Map.of("status", s)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    if (drainCoinsFirst) wallet.redeem(customer, wallet.balance(customer), UUID.randomUUID());

    Map preview = orderReturn(id, juice, 1, true).getBody();
    Map real = orderReturn(id, juice, 1, false).getBody();
    assertSame(preview, real, false);
    assertThat(n(preview.get("coinsRestored"))).as("the order spent coins").isPositive();
    if (drainCoinsFirst) assertThat(n(real.get("coinsClawedBack"))).isZero();
    else assertThat(n(real.get("coinsClawedBack"))).isPositive();

    Map secondPreview = orderReturn(id, juice, 1, true).getBody();
    Map second = orderReturn(id, juice, 1, false).getBody();
    assertSame(secondPreview, second, false);
    Map ricePreview = orderReturn(id, rice, 1, true).getBody();
    Map riceReal = orderReturn(id, rice, 1, false).getBody();
    assertSame(ricePreview, riceReal, false);
  }

  @Test void onlineKeepPolicy() { onlineParity("KEEP", false); }

  @Test void onlineClawbackPolicy() { onlineParity("CLAWBACK_BELOW_MINIMUM", false); }

  @Test void onlineWhenTheCustomerHasAlreadySpentTheEarnedCoins() { onlineParity("KEEP", true); }
}
