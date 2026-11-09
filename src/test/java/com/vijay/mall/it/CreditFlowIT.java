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

/** Credit (udhar) and hybrid payment at the shop counter, against real PostgreSQL. Credit never exists online. */
@SuppressWarnings({"unchecked", "rawtypes"})
class CreditFlowIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";
  /** price of one Basmati Rice */
  private static final long RICE = 12500;

  @BeforeEach
  void stock() { restock("Basmati Rice", 100); }

  private String cashier() { return token(CASHIER); }

  private Object riceId() { return productByName(cashier(), "Basmati Rice").get("id"); }

  private String mobile() { return "97" + String.format("%08d", (long) (Math.random() * 100_000_000L)); }

  private int stockOfRice() {
    return jdbc.queryForObject("select i.qty_on_hand from inventory i join product p on p.id = i.product_id where p.name_en = ?",
        Integer.class, "Basmati Rice");
  }

  private void approve(String mobile, long limit) {
    var res = call(HttpMethod.PUT, "/api/v1/admin/credit/limit", adminToken(), Map.of("mobile", mobile, "limitPaise", limit), null);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<Map> sell(String mobile, int rice, List<Map<String, Object>> payments) {
    Map<String, Object> cart = new HashMap<>(Map.of("items", List.of(Map.of("productId", riceId(), "qty", rice))));
    if (mobile != null) cart.put("customerMobile", mobile);
    return call(HttpMethod.POST, "/api/v1/pos/sales", cashier(), Map.of("cart", cart, "payments", payments), UUID.randomUUID().toString());
  }

  private static Map<String, Object> pay(String mode, long amount) { return Map.of("mode", mode, "amountPaise", amount); }

  private Map account(String mobile) { return get("/api/v1/pos/credit/account?mobile=" + mobile, cashier()).getBody(); }

  private long cashExpected() {
    return ((Number) get("/api/v1/pos/reports/day", cashier()).getBody().get("cashExpectedPaise")).longValue();
  }

  // ---------- giving credit ----------
  @Test
  void creditNeedsApprovalThenWorksAndTheCustomerSeesItOnTheirAccount() {
    String m = mobile();
    var refused = sell(m, 2, List.of(pay("CREDIT", 2 * RICE)));
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(refused.getBody().get("code")).isEqualTo("CREDIT_NOT_ALLOWED");

    approve(m, 50000);
    var sold = sell(m, 2, List.of(pay("CREDIT", 2 * RICE)));
    assertThat(sold.getStatusCode()).as(String.valueOf(sold.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(sold.getBody().get("paymentType")).isEqualTo("CREDIT");

    Map acct = account(m);
    assertThat(((Number) acct.get("balancePaise")).longValue()).isEqualTo(25000);
    assertThat(((Number) acct.get("availablePaise")).longValue()).isEqualTo(25000);

    // the customer signs in with the same mobile and the counter account is claimed
    post("/api/v1/auth/otp/send-mobile", null, Map.of("mobile", m));
    String token = (String) post("/api/v1/auth/otp/verify-mobile", null, Map.of("mobile", m, "otp", "12345")).getBody().get("accessToken");
    Map mine = get("/api/v1/credit", token).getBody();
    assertThat(((Number) mine.get("balancePaise")).longValue()).isEqualTo(25000);
    assertThat(((Number) mine.get("limitPaise")).longValue()).isEqualTo(50000);
    List<Map> ledger = (List<Map>) get("/api/v1/credit/ledger", token).getBody().get("items");
    assertThat(ledger).hasSize(1);
    assertThat(ledger.get(0).get("kind")).isEqualTo("SALE");
    assertThat(((Number) ledger.get(0).get("deltaPaise")).longValue()).isEqualTo(25000);
  }

  @Test
  void hybridBillPutsOnlyTheCreditShareOnTheAccount() {
    String m = mobile();
    approve(m, 50000);
    var sold = sell(m, 2, List.of(pay("CASH", 10000), pay("UPI", 5000), pay("CREDIT", 10000)));
    assertThat(sold.getStatusCode()).as(String.valueOf(sold.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(sold.getBody().get("paymentType")).isEqualTo("HYBRID");
    assertThat(((Number) account(m).get("balancePaise")).longValue()).isEqualTo(10000);
  }

  @Test
  void overTheLimitIsRefusedAndStockIsNotTaken() {
    String m = mobile();
    approve(m, 10000);
    int before = stockOfRice();
    var res = sell(m, 2, List.of(pay("CREDIT", 2 * RICE)));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(res.getBody().get("code")).isEqualTo("CREDIT_LIMIT_EXCEEDED");
    assertThat(((Map) res.getBody().get("details")).get("availablePaise")).isEqualTo(10000);
    assertThat(stockOfRice()).isEqualTo(before);
  }

  @Test
  void creditNeedsACustomerAndCannotBeUsedTwiceOnOneBill() {
    assertThat(sell(null, 1, List.of(pay("CREDIT", RICE))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    String m = mobile();
    approve(m, 50000);
    assertThat(sell(m, 2, List.of(pay("CREDIT", RICE), pay("CREDIT", RICE))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ---------- taking repayments ----------
  @Test
  void repaymentIsSplitOverModesIsIdempotentAndCannotExceedWhatIsOwed() {
    String m = mobile();
    approve(m, 50000);
    sell(m, 2, List.of(pay("CREDIT", 2 * RICE)));
    long cashBefore = cashExpected();

    Map<String, Object> body = Map.of("customerMobile", m, "payments", List.of(pay("CASH", 5000), pay("UPI", 5000)), "note", "part payment");
    String key = UUID.randomUUID().toString();
    var first = call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(), body, key);
    assertThat(first.getStatusCode()).as(String.valueOf(first.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat((String) first.getBody().get("receiptNo")).contains("-CR/");
    assertThat(((Number) first.getBody().get("amountPaise")).longValue()).isEqualTo(10000);
    assertThat(((Number) first.getBody().get("balanceDuePaise")).longValue()).isEqualTo(15000);

    var replay = call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(), body, key);
    assertThat(replay.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    assertThat(((Number) account(m).get("balancePaise")).longValue()).isEqualTo(15000);

    var over = call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(),
        Map.of("customerMobile", m, "payments", List.of(pay("CASH", 15001))), UUID.randomUUID().toString());
    assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(over.getBody().get("code")).isEqualTo("CREDIT_OVERPAYMENT");
    var viaCredit = call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(),
        Map.of("customerMobile", m, "payments", List.of(pay("CREDIT", 100))), UUID.randomUUID().toString());
    assertThat(viaCredit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    // the cash handed over is in the drawer: it counts towards the cash that should be there
    assertThat(cashExpected()).isEqualTo(cashBefore + 5000);
    List<Map> ledger = (List<Map>) get("/api/v1/pos/credit/ledger?mobile=" + m, cashier()).getBody().get("items");
    assertThat(ledger).extracting(e -> e.get("kind")).containsExactly("PAYMENT", "SALE");
  }

  // ---------- goods coming back ----------
  @Test
  void returnOfAGoodsBoughtOnCreditReducesWhatIsOwedInsteadOfPayingCash() {
    String m = mobile();
    approve(m, 50000);
    Map sale = sell(m, 2, List.of(pay("CREDIT", 2 * RICE))).getBody();
    Object itemId = ((List<Map>) sale.get("items")).get(0).get("id");

    var res = call(HttpMethod.POST, "/api/v1/pos/sales/" + sale.get("id") + "/returns", cashier(),
        Map.of("items", List.of(Map.of("saleItemId", itemId, "qty", 1))), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(((Number) res.getBody().get("creditAdjustedPaise")).longValue()).isGreaterThan(0);
    assertThat((List) res.getBody().get("refunds")).isEmpty();
    long adjusted = ((Number) res.getBody().get("creditAdjustedPaise")).longValue();
    assertThat(((Number) account(m).get("balancePaise")).longValue()).isEqualTo(25000 - adjusted);
  }

  // ---------- admin ----------
  @Test
  void adminCanLowerTheLimitWriteOffAndSeeEveryoneWhoOwes() {
    String m = mobile();
    approve(m, 50000);
    sell(m, 2, List.of(pay("CREDIT", 2 * RICE)));
    Object id = account(m).get("customerId");
    String admin = adminToken();

    var low = call(HttpMethod.PUT, "/api/v1/admin/credit/accounts/" + id + "/limit", admin, Map.of("limitPaise", 10000), null);
    assertThat(((Number) low.getBody().get("availablePaise")).longValue()).isZero(); // owes more than the new limit

    var off = post("/api/v1/admin/credit/accounts/" + id + "/adjust", admin, Map.of("amountPaise", -5000, "note", "goodwill write-off"));
    assertThat(((Number) off.getBody().get("balancePaise")).longValue()).isEqualTo(20000);
    assertThat(post("/api/v1/admin/credit/accounts/" + id + "/adjust", admin, Map.of("amountPaise", -99999, "note", "too much")).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(post("/api/v1/admin/credit/accounts/" + id + "/adjust", admin, Map.of("amountPaise", -100)).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    List<Map> owing = (List<Map>) get("/api/v1/admin/credit/accounts?size=200", admin).getBody().get("items");
    assertThat(owing).anyMatch(d -> m.equals(d.get("mobile")) && ((Number) d.get("balancePaise")).longValue() == 20000);
  }

  // ---------- reports ----------
  @Test
  void reportsShowCreditGivenRecoveredAndHowBillsWerePaid() {
    String m = mobile();
    approve(m, 50000);
    sell(m, 2, List.of(pay("CREDIT", 2 * RICE)));
    sell(m, 2, List.of(pay("CASH", 10000), pay("CREDIT", 15000)));
    call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(),
        Map.of("customerMobile", m, "payments", List.of(pay("UPI", 5000))), UUID.randomUUID().toString());

    Map day = get("/api/v1/pos/reports/day", cashier()).getBody();
    assertThat(((Number) day.get("creditGivenPaise")).longValue()).isGreaterThanOrEqualTo(40000);
    assertThat(((Number) day.get("creditRecoveredPaise")).longValue()).isGreaterThanOrEqualTo(5000);
    assertThat((Map) day.get("paidByMode")).doesNotContainKey("CREDIT");

    String admin = adminToken();
    Map report = get("/api/v1/admin/reports/credit", admin).getBody();
    assertThat(report).containsKeys("outstandingPaise", "customersWithDues", "givenPaise", "recoveredPaise", "recoveredByMode",
        "salesByPaymentType", "byDay", "topDebtors");
    assertThat(((Number) report.get("givenPaise")).longValue()).isGreaterThanOrEqualTo(40000);
    assertThat(((Number) report.get("outstandingPaise")).longValue()).isGreaterThanOrEqualTo(35000);
    assertThat((List<Map>) report.get("salesByPaymentType")).extracting(r -> r.get("type")).contains("CREDIT", "HYBRID");
    assertThat((List) report.get("byDay")).hasSize(7);
    assertThat(get("/api/v1/admin/reports/credit?period=month", admin).getStatusCode()).isEqualTo(HttpStatus.OK);

    Map sales = get("/api/v1/admin/reports/summary", admin).getBody();
    assertThat(sales).containsKeys("creditGivenPaise", "creditRecoveredPaise", "outstandingCreditPaise");
    assertThat(((Number) sales.get("creditGivenPaise")).longValue()).isGreaterThanOrEqualTo(40000);
  }

  // ---------- never online, and who may do what ----------
  @Test
  void creditAndHybridAreRefusedOnlineCheckout() {
    restock("Potato Chips", 5);
    String token = token(freshEmail());
    Object pid = productByName(token, "Potato Chips").get("id");
    for (String method : List.of("CREDIT", "HYBRID")) {
      var res = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", List.of(Map.of("productId", pid, "qty", 1)),
          "paymentMethod", method, "fulfilment", "PICKUP", "expectedTotalPaise", 1), UUID.randomUUID().toString());
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(String.valueOf(res.getBody())).contains("shop counter");
    }
    assertThat(getList("/api/v1/payment-methods", token)).extracting(x -> x.get("code")).doesNotContain("CREDIT", "HYBRID");
  }

  @Test
  void accessRules() {
    String customer = token(freshEmail());
    assertThat(get("/api/v1/pos/credit/account?mobile=9000000001", customer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/credit/limit", cashier(), Map.of("mobile", "9000000001", "limitPaise", 1), null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/credit/accounts", cashier()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/credit", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/v1/credit", customer).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/pos/credit/account?mobile=9111111111", cashier()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ---------- payment modes and the repayment receipt ----------
  @Test
  void aMissingOrUnknownRepaymentModeIsA400_notA500_andSpacesAndCaseAreForgiven() {
    String m = mobile();
    approve(m, 50000);
    sell(m, 2, List.of(pay("CREDIT", 2 * RICE)));
    java.util.Map<String, Object> noMode = new HashMap<>();
    noMode.put("amountPaise", 100);
    var missing = call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(),
        Map.of("customerMobile", m, "payments", List.of(noMode)), UUID.randomUUID().toString());
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map fields = (Map) ((Map) missing.getBody().get("details")).get("fields");
    assertThat(fields.get("payments[0].mode")).isEqualTo("payment mode must be CASH, UPI or CARD (got none)");
    var unknown = call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(),
        Map.of("customerMobile", m, "payments", List.of(pay("CASH", 100), pay("xyz", 100))), UUID.randomUUID().toString());
    assertThat(((Map) ((Map) unknown.getBody().get("details")).get("fields")).get("payments[1].mode"))
        .isEqualTo("payment mode must be CASH, UPI or CARD (got \"xyz\")");
    var spaced = call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(),
        Map.of("customerMobile", m, "payments", List.of(pay(" cash ", 100))), UUID.randomUUID().toString());
    assertThat(spaced.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(((Map) ((List) spaced.getBody().get("payments")).get(0)).get("mode")).isEqualTo("CASH");
  }

  @Test
  void repaymentReceiptShowsModesAndTheBalanceBeforeAndAfter() {
    String m = mobile();
    approve(m, 50000);
    sell(m, 2, List.of(pay("CREDIT", 2 * RICE)));
    var paid = call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(),
        Map.of("customerMobile", m, "payments", List.of(pay("CASH", 6000), pay("UPI", 4000)), "note", "part"), UUID.randomUUID().toString());
    // a later payment must not change what the first receipt says
    call(HttpMethod.POST, "/api/v1/pos/credit/payments", cashier(), Map.of("customerMobile", m, "payments", List.of(pay("CASH", 1000))),
        UUID.randomUUID().toString());
    String url = "/api/v1/pos/credit/payments/" + paid.getBody().get("id") + "/receipt";
    var receipt = get(url + "?width=32", cashier());
    assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(receipt.getBody().get("width")).isEqualTo(32);
    String text = (String) receipt.getBody().get("text");
    assertThat(text).contains((String) paid.getBody().get("receiptNo"), m, "Balance before", "250.00", "Paid CASH", "60.00", "Paid UPI",
        "40.00", "Amount paid", "100.00", "Balance after", "150.00", "Note: part");
    assertThat(get(url + "?width=48", adminToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get(url + "?width=33", cashier()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/pos/credit/payments/" + UUID.randomUUID() + "/receipt", cashier()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get(url, token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
