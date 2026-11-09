package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.payment.app.SettlementService;
import com.vijay.mall.payment.domain.SettlementProvider.Detail;
import com.vijay.mall.payment.domain.SettlementProvider.Item;
import com.vijay.mall.payment.domain.SettlementProvider.ItemKind;
import com.vijay.mall.payment.domain.SettlementProvider.PaymentDetail;
import com.vijay.mall.payment.domain.SettlementProvider.Summary;
import com.vijay.mall.support.BankFakes;
import com.vijay.mall.support.FakeSettlementProvider;
import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.json.JsonMapper;

/** Online payment tagging and detail, settlement pull (idempotent, locked), CSV import, exceptions and the bank reports, on real PostgreSQL. */
@Import(BankFakes.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class SettlementIT extends IntegrationTestBase {
  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry r) { r.add("app.payment.online-provider", () -> "fake"); }

  @Autowired FakeSettlementProvider fake;
  @Autowired SettlementService settlements;

  private String admin;
  private Map bank;
  private Map gw;

  private Map account(String name, String kind, Object settlesTo) {
    Map<String, Object> body = new HashMap<>(Map.of("name", name + " " + UUID.randomUUID().toString().substring(0, 8), "kind", kind, "provider", "fake"));
    if (settlesTo != null) body.put("settlesToAccountId", settlesTo);
    return call(HttpMethod.POST, "/api/v1/admin/money-accounts", admin, body, null).getBody();
  }

  private void config(String key, String value) {
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(Map.of("key", key, "value", value))), null).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @BeforeEach
  void setUp() {
    fake.reset();
    admin = adminToken();
    restock("Orange Juice", 50);
    setPaymentMethod(admin, "ONLINE", true);
    bank = account("Bank", "BANK", null);
    gw = account("Gateway", "GATEWAY", bank.get("id"));
    config("payment.gatewayAccountId", (String) gw.get("id"));
  }

  @AfterEach
  void tearDown() {
    fake.reset();
    config("payment.gatewayAccountId", "");
  }

  private record Placed(String token, String orderId, long total, String ref) {}

  private Placed placeAndCapture(int qty, long capturedAmountOrNegative) {
    String token = token(freshEmail());
    Object pid = productByName(token, "Orange Juice").get("id");
    var items = List.of(Map.of("productId", pid, "qty", qty));
    long total = ((Number) post("/api/v1/cart/quote", token, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP")).getBody().get("totalPaise")).longValue();
    var res = call(HttpMethod.POST, "/api/v1/orders", token, Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP", "paymentMethod", "ONLINE",
        "expectedTotalPaise", total), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String orderId = (String) res.getBody().get("id");
    String ev = "ev-" + UUID.randomUUID();
    long amount = capturedAmountOrNegative < 0 ? total : capturedAmountOrNegative;
    fake.paymentDetails.put("pay_" + ev, new PaymentDetail("upi", "payer@upi", null, null, "HDFC", 200L, 36L));
    var cap = client().post().uri("/api/v1/payments/webhook/fake").contentType(MediaType.APPLICATION_JSON).headers(h -> h.set("x-fake-signature", "ok"))
        .body(Map.of("eventId", ev, "type", "captured", "providerOrderId", "fake_" + orderId, "amount", amount)).retrieve().toEntity(String.class);
    assertThat(cap.getStatusCode()).isEqualTo(HttpStatus.OK);
    return new Placed(token, orderId, total, "pay_" + ev);
  }

  private Map paymentRow(String orderId) { return jdbc.queryForMap("select * from payment where order_id = ?::uuid", orderId); }

  private Item payItem(String entity, String payRef, long gross, long fee, long tax) {
    return new Item(entity, ItemKind.PAYMENT, payRef, null, gross, fee, tax, gross - fee - tax, "upi", Instant.now(), "UTR-" + entity, null);
  }

  private Summary summary(String id, String status) { return new Summary(id, "UTRS-" + id, 0, 0, 0, 0, status, Instant.now()); }

  private void register(String id, String status, Item... items) {
    long gross = 0, fee = 0, tax = 0, net = 0;
    for (Item i : items) {
      gross += i.grossPaise();
      fee += i.feePaise();
      tax += i.taxPaise();
      net += i.netPaise();
    }
    Summary s = new Summary(id, "UTRS-" + id, gross, fee, tax, net, status, Instant.now());
    fake.settlements.removeIf(x -> x.id().equals(id));
    fake.settlements.add(s);
    fake.details.put(id, new Detail(s, List.of(items)));
  }

  private String sid() { return "setl_" + UUID.randomUUID().toString().substring(0, 8); }

  /** Every row of the unsettled report, read page by page, so the test does not depend on how much other tests left behind. */
  private List<Map> unsettled(boolean overdueOnly) {
    List<Map> out = new java.util.ArrayList<>();
    for (int page = 0; page < 40; page++) {
      Map body = get("/api/v1/admin/settlements/unsettled?size=50&page=" + page + "&overdueOnly=" + overdueOnly, admin).getBody();
      out.addAll((List<Map>) body.get("items"));
      if (!Boolean.TRUE.equals(body.get("hasMore"))) break;
    }
    return out;
  }

  private List<Map> exceptions(String query) { return (List<Map>) get("/api/v1/admin/settlements/exceptions?size=50&" + query, admin).getBody().get("items"); }

  @Test void onlinePaymentIsTaggedDetailedSettledAndReported() {
    Placed p = placeAndCapture(1, -1);
    Map row = paymentRow(p.orderId());
    assertThat(row.get("money_account_id").toString()).isEqualTo(gw.get("id"));
    assertThat(row.get("captured_at")).isNotNull();
    assertThat(row).containsEntry("instrument", "upi").containsEntry("upi_vpa", "payer@upi").containsEntry("bank", "HDFC").containsEntry("fee_paise", 200L).containsEntry("tax_paise", 36L);
    assertThat(row.get("card_last4")).isNull();

    List<Map> payers = (List<Map>) get("/api/v1/admin/payments/by-order/" + p.orderId(), admin).getBody().get("_array");
    assertThat(payers).singleElement().satisfies(x -> assertThat(x).containsEntry("instrument", "upi").containsEntry("upiVpa", "payer@upi").containsEntry("accountName", gw.get("name"))
        .doesNotContainKeys("cardNumber", "pan"));
    assertThat(get("/api/v1/admin/payments/by-order/" + p.orderId(), token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    // a refund goes back to the same account
    post("/api/v1/admin/orders/" + p.orderId() + "/status", admin, Map.of("status", "CONFIRMED"));
    post("/api/v1/admin/orders/" + p.orderId() + "/reject", admin, Map.of("reason", "cannot deliver"));
    assertThat(jdbc.queryForObject("select money_account_id::text from refund where payment_id = ?::uuid", String.class, row.get("id").toString())).isEqualTo(gw.get("id"));
    String refundRef = jdbc.queryForObject("select provider_ref from refund where payment_id = ?::uuid and provider_ref is not null", String.class, row.get("id").toString());

    // a second paid order, with an amount that disagrees with the settlement, and a third that is missing from the settlement
    Placed other = placeAndCapture(2, -1);
    String setId = sid();
    Item refundItem = new Item("rf1", ItemKind.REFUND, p.ref(), refundRef, p.total(), 0, 0, -p.total(), "upi", Instant.now(), "UTR-RF", null);
    register(setId, "processed", payItem("e1", p.ref(), p.total(), 200, 36), refundItem, payItem("e2", other.ref(), other.total() + 7, 100, 18),
        payItem("e3", "pay_unknown_" + setId, 5000, 0, 0),
        new Item("a1", ItemKind.ADJUSTMENT, null, null, 100, 0, 0, -100, null, Instant.now(), null, null),
        new Item("rf2", ItemKind.REFUND, "pay_x", "refund_unknown_" + setId, 300, 0, 0, -300, null, Instant.now(), null, null),
        new Item("rf3", ItemKind.REFUND, "pay_x", null, 300, 0, 0, -300, null, Instant.now(), null, null));

    var first = post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake", "days", 7));
    assertThat(first.getBody()).containsEntry("settlementsSeen", 1).containsEntry("settlementsStored", 1).containsEntry("itemsStored", 7).containsEntry("skipped", false);
    Map paid = paymentRow(p.orderId());
    assertThat(paid).containsEntry("settlement_ref", setId).containsEntry("utr", "UTR-e1").containsEntry("fee_paise", 200L).containsEntry("tax_paise", 36L)
        .containsEntry("net_paise", p.total() - 236);
    assertThat(paymentRow(other.orderId()).get("settlement_ref")).as("an amount mismatch is not settled").isNull();

    // the exceptions: unmatched payment, unmatched refund x2, amount mismatch
    assertThat(exceptions("status=OPEN&kind=UNMATCHED_ITEM")).extracting(e -> e.get("reference")).contains("pay_unknown_" + setId, "refund_unknown_" + setId);
    Map mismatch = exceptions("kind=AMOUNT_MISMATCH").stream().filter(e -> other.ref().equals(e.get("reference"))).findFirst().orElseThrow();
    assertThat(mismatch).containsEntry("expectedPaise", (int) other.total()).containsEntry("actualPaise", (int) other.total() + 7);

    // the same pull again changes nothing and does not even ask for the detail
    int calls = fake.detailCalls;
    var again = post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake"));
    assertThat(again.getBody()).containsEntry("settlementsSeen", 1).containsEntry("settlementsStored", 0);
    assertThat(fake.detailCalls).isEqualTo(calls);
    // a changed status is read again, but no item is stored twice
    register(setId, "reversed", payItem("e1", p.ref(), p.total(), 200, 36));
    var changed = post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake", "days", 1));
    assertThat(changed.getBody()).containsEntry("settlementsStored", 1).containsEntry("itemsStored", 0);
    assertThat(jdbc.queryForObject("select count(*) from settlement_item i join settlement s on s.id = i.settlement_id where s.provider_settlement_id = ?", Integer.class, setId)).isEqualTo(7);

    // list, detail, items
    Map list = get("/api/v1/admin/settlements?size=50&accountId=" + gw.get("id") + "&provider=fake&source=gateway_api", admin).getBody();
    Map header = ((List<Map>) list.get("items")).stream().filter(s -> setId.equals(s.get("providerSettlementId"))).findFirst().orElseThrow();
    assertThat(header).containsEntry("items", 7).containsEntry("unmatchedItems", 4).containsEntry("accountName", gw.get("name")).containsEntry("status", "reversed");
    String id = (String) header.get("id");
    assertThat(get("/api/v1/admin/settlements/" + id, admin).getBody()).containsEntry("utr", "UTRS-" + setId);
    List<Map> items = (List<Map>) get("/api/v1/admin/settlements/" + id + "/items?size=50", admin).getBody().get("items");
    assertThat(items).extracting(i -> i.get("orderNo")).contains(jdbc.queryForObject("select order_no from orders where id = ?::uuid", String.class, p.orderId()));
    assertThat(items).filteredOn(i -> "e1".equals(i.get("providerEntityId"))).singleElement().satisfies(i -> assertThat(i).containsEntry("matched", true).containsEntry("orderId", p.orderId()));
    assertThat((List<Map>) get("/api/v1/admin/settlements/" + id + "/items?unmatchedOnly=true", admin).getBody().get("items")).hasSize(4).allMatch(i -> Boolean.FALSE.equals(i.get("matched")));
    assertThat(get("/api/v1/admin/settlements/" + UUID.randomUUID(), admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/settlements/" + UUID.randomUUID() + "/items", admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/settlements?source=PIGEON", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    // reports
    List<Map> receipts = (List<Map>) get("/api/v1/admin/bank-reports/receipts?size=50", admin).getBody().get("items");
    assertThat(receipts).filteredOn(r -> gw.get("id").equals(r.get("moneyAccountId"))).singleElement().satisfies(r -> assertThat(r).containsEntry("method", "UPI")
        .containsEntry("receipts", 2).containsEntry("grossPaise", (int) (p.total() + other.total())).containsEntry("refundedPaise", (int) p.total()));
    List<Map> fees = (List<Map>) get("/api/v1/admin/bank-reports/fees?size=50", admin).getBody().get("items");
    assertThat(fees).filteredOn(f -> gw.get("id").equals(f.get("moneyAccountId"))).singleElement().satisfies(f -> assertThat(f).containsEntry("payments", 2)
        .containsEntry("feePaise", 400).containsEntry("taxPaise", 72).containsEntry("missingFee", 0));
    // the gateway's book shows receipts, the refund, the payout and the fee; the bank's shows the payout arriving
    String book = get("/api/v1/admin/money-accounts/" + gw.get("id") + "/book?size=50", admin).getBody().toString();
    assertThat(book).contains("ONLINE_PAYMENT", "ONLINE_REFUND", "SETTLEMENT", "FEE");
    Map bankBook = get("/api/v1/admin/money-accounts/" + bank.get("id") + "/book?size=50", admin).getBody();
    assertThat(((Map) bankBook.get("lines")).get("items").toString()).contains("PAYOUT", "direction=IN");
    Map all = get("/api/v1/admin/receipts?provider=FAKE&account=" + gw.get("id") + "&method=upi&size=50", admin).getBody();
    assertThat((List<Map>) all.get("items")).hasSize(2).allSatisfy(r -> assertThat(r).containsEntry("source", "ONLINE_ORDER").containsEntry("payer", "pa***@upi")
        .containsEntry("method", "UPI").containsEntry("provider", "fake"));
    assertThat((List<Map>) get("/api/v1/admin/receipts/summary", admin).getBody().get("_array")).anySatisfy(t -> assertThat(t).containsEntry("provider", "fake")
        .containsEntry("moneyAccountId", gw.get("id")).containsEntry("receipts", 2));
    assertThat(get("/api/v1/admin/receipts?size=5&provider=%20&method=%20", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/admin/bank-reports/receipts?from=2020-01-01", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test void pullingIsGuardedAgainstBadInputsLocksAndProviderFailures() {
    assertThat(post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "nobody")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post("/api/v1/admin/settlements/sync", admin, Map.of("days", 3)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake", "days", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake", "days", 61)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "manual")).getBody()).containsEntry("settlementsSeen", 0);

    fake.configured = false;
    var off = post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake"));
    assertThat(off.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(off.getBody()).containsEntry("code", "SETTLEMENT_NOT_CONFIGURED");
    fake.configured = true;

    // another instance holds the claim
    jdbc.update("insert into settlement_sync_state (provider, locked_until) values ('fake', now() + interval '10 minutes') "
        + "on conflict (provider) do update set locked_until = excluded.locked_until");
    assertThat(post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake")).getBody()).containsEntry("skipped", true);
    jdbc.update("update settlement_sync_state set locked_until = now() - interval '1 minute' where provider = 'fake'");

    // a provider failure is remembered and releases the claim; the next good run clears it
    fake.failListing = true;
    assertThat(post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake")).getStatusCode().is5xxServerError()).isTrue();
    assertThat(jdbc.queryForObject("select last_error from settlement_sync_state where provider = 'fake'", String.class)).contains("provider down");
    fake.failListing = false;
    String a = sid();
    String b = sid();
    register(a, "processed");
    register(b, "processed");
    fake.onePerPage = true;
    var ok = post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake"));
    assertThat(ok.getBody()).containsEntry("settlementsSeen", 2).containsEntry("settlementsStored", 2).containsEntry("itemsStored", 0);
    assertThat(jdbc.queryForObject("select last_error from settlement_sync_state where provider = 'fake'", String.class)).isNull();
    // a settlement that has no items yet is read again until it has some
    int calls = fake.detailCalls;
    post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake"));
    assertThat(fake.detailCalls).isGreaterThan(calls);
    // a listed settlement the provider cannot describe is stored empty
    fake.settlements.add(summary("only_listed_" + a, "processed"));
    assertThat(post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake")).getBody()).containsEntry("settlementsStored", 3);
  }

  @Test void aSettlementProcessedWebhookPullsThatSettlement() {
    String id = sid();
    Placed p = placeAndCapture(1, -1);
    register(id, "processed", payItem("w1", p.ref(), p.total(), 100, 18));
    Map hook = Map.of("eventId", "wh-" + id, "type", "settlement", "settlementId", id);
    for (int i = 0; i < 2; i++) { // a repeated delivery is ignored
      var res = client().post().uri("/api/v1/payments/webhook/fake").contentType(MediaType.APPLICATION_JSON).headers(h -> h.set("x-fake-signature", "ok")).body(hook)
          .retrieve().toEntity(String.class);
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    assertThat(paymentRow(p.orderId()).get("settlement_ref")).isEqualTo(id);
    assertThat(jdbc.queryForObject("select count(*) from settlement where provider_settlement_id = ?", Integer.class, id)).isEqualTo(1);

    // already complete: not read again; unknown to the provider: nothing; failing provider: logged only; provider off: nothing
    int calls = fake.detailCalls;
    settlements.syncOne("fake", id);
    assertThat(fake.detailCalls).isEqualTo(calls + 1);
    settlements.syncOne("fake", "unknown_" + id);
    settlements.syncOne("fake", "boom_" + id);
    fake.configured = false;
    settlements.syncOne("fake", id);
    fake.configured = true;
    assertThat(jdbc.queryForObject("select count(*) from settlement where provider_settlement_id like 'unknown_%' or provider_settlement_id like 'boom_%'", Integer.class)).isZero();
  }

  @Test void capturedPaymentsNobodySettledBecomeOverdueExceptionsAndCloseWhenSettled() {
    Placed p = placeAndCapture(1, -1);
    Placed fresh = placeAndCapture(1, -1);
    jdbc.update("update payment set captured_at = now() - interval '10 days' where order_id = ?::uuid", p.orderId());
    assertThat(settlements.checkOverdue()).isGreaterThanOrEqualTo(1);
    assertThat(settlements.checkOverdue()).as("raised once").isZero();
    String payId = paymentRow(p.orderId()).get("id").toString();
    assertThat(exceptions("kind=OVERDUE_UNSETTLED&status=OPEN")).anySatisfy(e -> assertThat(e).containsEntry("paymentId", payId));
    assertThat(exceptions("kind=OVERDUE_UNSETTLED&status=OPEN")).noneSatisfy(e -> assertThat(e).containsEntry("paymentId", paymentRow(fresh.orderId()).get("id").toString()));

    List<Map> overdue = unsettled(true);
    assertThat(overdue).anySatisfy(u -> assertThat((Map) u.get("row")).containsEntry("reference", jdbc.queryForObject("select order_no from orders where id = ?::uuid", String.class, p.orderId())));
    assertThat(overdue).allMatch(u -> Boolean.TRUE.equals(u.get("overdue")));
    List<Map> all = unsettled(false);
    assertThat(all).anyMatch(u -> Boolean.FALSE.equals(u.get("overdue")));

    String setId = sid();
    register(setId, "processed", payItem("o1", p.ref(), p.total(), 0, 0));
    post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake"));
    assertThat(exceptions("kind=OVERDUE_UNSETTLED&status=RESOLVED")).anySatisfy(e -> assertThat(e).containsEntry("paymentId", payId).containsEntry("note", "Settled"));
    assertThat(unsettled(true))
        .noneMatch(u -> payId.equals(((Map) u.get("row")).get("id")));
  }

  @Test void exceptionsAreResolvedWithANote() {
    String setId = sid();
    register(setId, "processed", payItem("x1", "pay_nope_" + setId, 100, 0, 0));
    post("/api/v1/admin/settlements/sync", admin, Map.of("provider", "fake"));
    Map e = exceptions("status=OPEN&kind=UNMATCHED_ITEM").stream().filter(x -> ("pay_nope_" + setId).equals(x.get("reference"))).findFirst().orElseThrow();
    String url = "/api/v1/admin/settlements/exceptions/" + e.get("id") + "/resolve";
    assertThat(post(url, admin, Map.of("note", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(url, admin, Map.of("note", "n".repeat(301))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/settlements/exceptions/" + UUID.randomUUID() + "/resolve", admin, Map.of("note", "x")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var done = post(url, admin, Map.of("note", "paid by cash, matched by hand"));
    assertThat(done.getBody()).containsEntry("status", "RESOLVED").containsEntry("note", "paid by cash, matched by hand");
    assertThat(post(url, admin, Map.of("note", "second try")).getBody()).containsEntry("note", "paid by cash, matched by hand");
    assertThat(get("/api/v1/admin/settlements/exceptions?status=DONE", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/settlements/exceptions?kind=ODD", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test void paymentDetailsAreFilledLaterByTheJobAndNeverBreakOnProviderFailure() {
    Placed p = placeAndCapture(1, -1);
    Placed boom = placeAndCapture(1, -1);
    Placed none = placeAndCapture(1, -1);
    jdbc.update("update payment set detail_fetched_at = null, upi_vpa = null, fee_paise = null where order_id in (?::uuid, ?::uuid, ?::uuid)", p.orderId(), boom.orderId(), none.orderId());
    jdbc.update("update payment set provider_ref = 'boom' where order_id = ?::uuid", boom.orderId());
    jdbc.update("update payment set provider_ref = 'nothing_known' where order_id = ?::uuid", none.orderId());
    jdbc.update("update payment set detail_fetched_at = now() where detail_fetched_at is null and order_id not in (?::uuid, ?::uuid, ?::uuid)", p.orderId(), boom.orderId(), none.orderId());
    settlements.fillPaymentDetails();
    assertThat(paymentRow(p.orderId())).containsEntry("upi_vpa", "payer@upi").containsEntry("fee_paise", 200L);
    assertThat(paymentRow(none.orderId()).get("detail_fetched_at")).as("looked up, nothing known").isNotNull();
    assertThat(paymentRow(boom.orderId()).get("detail_fetched_at")).as("provider failed: tried again later").isNull();
    // a provider without keys (razorpay here) is left alone
    settlements.fillDetail(UUID.randomUUID(), "razorpay", "pay_x");
    settlements.fillDetail(UUID.randomUUID(), "unknownbrand", "pay_x");
  }

  @Test void unsettledCounterCardLinesAreListedUntilTheirStatementArrives() {
    restock("Basmati Rice", 20);
    String cashier = token("cashier@vijaysupermall.local");
    Map terminal = account("Machine", "CARD_TERMINAL", bank.get("id"));
    List<Map<String, Object>> products = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", cashier).getBody().get("items");
    Object rice = products.stream().filter(x -> "Basmati Rice".equals(x.get("nameEn")) && "1 kg".equals(x.get("packSize"))).findFirst().orElseThrow().get("id");
    String ref = "APPR-" + UUID.randomUUID().toString().substring(0, 8);
    var sale = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", rice, "qty", 1))),
        "payments", List.of(Map.of("mode", "CARD", "amountPaise", 12500, "reference", ref, "moneyAccountId", terminal.get("id")))), UUID.randomUUID().toString());
    assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    List<Map> before = unsettled(false);
    assertThat(before).anySatisfy(u -> assertThat((Map) u.get("row")).containsEntry("source", "COUNTER").containsEntry("reference", sale.getBody().get("invoiceNo")).containsEntry("accountName", terminal.get("name")));

    String csv = "date,reference,amount,fee,tax,description\n" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")) + "," + ref + ",125.00,2.00,0.36,machine batch\n";
    var imported = upload(csv, terminal.get("id"), Map.of("utr", "BATCH-1"));
    assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(imported.getBody()).containsEntry("matched", 1).containsEntry("unmatched", 0).containsEntry("alreadyImported", false);
    assertThat(unsettled(false)).noneMatch(u -> ref.equals(((Map) u.get("row")).get("reference")));
    // the terminal's book shows the receipt and the payout leaving it; the bank's shows the net arriving
    assertThat(get("/api/v1/admin/money-accounts/" + terminal.get("id") + "/book?size=50", admin).getBody().toString()).contains("COUNTER_PAYMENT", "SETTLEMENT");
  }

  // ---------- CSV import ----------
  private ResponseEntity<Map> upload(String csv, Object accountId, Map<String, String> extra) {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new ByteArrayResource(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
      @Override public String getFilename() { return "statement.csv"; }
    });
    if (accountId != null) parts.add("moneyAccountId", accountId.toString());
    extra.forEach(parts::add);
    var res = client().post().uri("/api/v1/admin/settlements/import").headers(h -> h.setBearerAuth(admin)).contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
        .retrieve().toEntity(String.class);
    Map body = res.getBody() == null ? null : JsonMapper.builder().build().readValue(res.getBody(), Map.class);
    return new ResponseEntity<>(body, res.getHeaders(), res.getStatusCode());
  }

  @Test void statementCsvIsImportedMatchedOnceAndRejectedWhenBroken() {
    Placed p = placeAndCapture(1, -1);
    Placed q = placeAndCapture(1, -1);
    String ref = "UTR-" + UUID.randomUUID().toString().substring(0, 8);
    String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    String csv = "\uFEFFDate;Ref;Credit;Charge;GST;Narration\n"
        + today + ";" + p.ref() + ";\"" + String.format(java.util.Locale.ROOT, "%,.2f", p.total() / 100.0) + "\";1.00;0.18;\"quoted; text\"\n"
        + today + ";" + q.ref() + ";" + (q.total() + 100) / 100.0 + ";;;wrong amount\n"
        + today + ";" + ref + ";55.00;;;nobody knows\n"
        + today + ";;-20.50;;;bank charge\n";
    Map<String, String> map = Map.of("dateColumn", "date", "referenceColumn", "ref", "amountColumn", "credit", "feeColumn", "charge", "taxColumn", "gst",
        "descriptionColumn", "narration", "dateFormat", "dd/MM/yyyy", "delimiter", ";", "provider", "Acquirer-X");
    var first = upload(csv, bank.get("id"), map);
    assertThat(first.getStatusCode()).as(String.valueOf(first.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody()).containsEntry("rows", 4).containsEntry("matched", 2).containsEntry("unmatched", 2).containsEntry("alreadyImported", false);
    assertThat(paymentRow(p.orderId())).containsEntry("fee_paise", 100L).containsEntry("tax_paise", 18L);
    assertThat(paymentRow(q.orderId()).get("settlement_ref")).isNull();
    assertThat(exceptions("kind=AMOUNT_MISMATCH")).anySatisfy(e -> assertThat(e).containsEntry("reference", q.ref()));
    assertThat(exceptions("kind=UNMATCHED_ITEM")).anySatisfy(e -> assertThat(e).containsEntry("reference", ref));
    var repeat = upload(csv, bank.get("id"), map);
    assertThat(repeat.getBody()).containsEntry("alreadyImported", true).containsEntry("settlementId", first.getBody().get("settlementId"));
    List<Map> listed = (List<Map>) get("/api/v1/admin/settlements?size=50&source=CSV&provider=acquirer-x", admin).getBody().get("items");
    assertThat(listed).anySatisfy(s -> assertThat(s).containsEntry("source", "CSV").containsEntry("status", "IMPORTED"));

    // paise amounts, tab separated, defaults for the column names
    var paise = upload("date\treference\tamount\n2026-11-09\t" + ref + "-2\t5000\n", bank.get("id"), Map.of("delimiter", "tab", "amountUnit", "PAISE"));
    assertThat(paise.getBody()).containsEntry("rows", 1).containsEntry("netPaise", 5000);

    // refusals
    assertThat(upload(csv, null, map).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(upload(csv, UUID.randomUUID(), map).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(upload("date,reference\n2026-11-09,x\n", bank.get("id"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var broken = upload("date,reference,amount\nnot-a-date,a,1\n2026-11-09,b,abc\n2026-11-09,c,1.234\n,d,1\n2026-11-09,e,\n", bank.get("id"), Map.of());
    assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(broken.getBody()).containsEntry("code", "CSV_INVALID");
    assertThat((List<String>) ((Map) broken.getBody().get("details")).get("problems")).hasSize(5).first().asString().startsWith("row 2");
    assertThat(upload("", bank.get("id"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(upload("date,reference,amount\n", bank.get("id"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(upload(csv, bank.get("id"), Map.of("dateFormat", "bbbb")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(upload("date,reference,amount\n2026-11-09,a,1\n", bank.get("id"), Map.of("provider", "p".repeat(41))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test void gatewayAccountFallsBackToTheProviderLookupWhenNoSettingIsSaved() {
    config("payment.gatewayAccountId", "");
    String expected = jdbc.queryForObject("select id::text from money_account where active and kind = 'GATEWAY' and provider = 'fake' order by created_at, id limit 1", String.class);
    Placed p = placeAndCapture(1, -1);
    assertThat(paymentRow(p.orderId()).get("money_account_id").toString()).isEqualTo(expected);
    // a saved account that was switched off is ignored too
    config("payment.gatewayAccountId", (String) gw.get("id"));
    post("/api/v1/admin/money-accounts/" + gw.get("id") + "/deactivate", admin, null);
    String expectedNow = jdbc.queryForObject("select id::text from money_account where active and kind = 'GATEWAY' and provider = 'fake' order by created_at, id limit 1", String.class);
    Placed q = placeAndCapture(1, -1);
    assertThat(paymentRow(q.orderId()).get("money_account_id").toString()).isEqualTo(expectedNow);
    post("/api/v1/admin/money-accounts/" + gw.get("id") + "/activate", admin, null);
  }

  @Test void aFailingProviderDoesNotBreakTheNightlyRun() {
    fake.failListing = true;
    settlements.nightly();
    fake.failListing = false;
    assertThat(jdbc.queryForObject("select last_error from settlement_sync_state where provider = 'fake'", String.class)).contains("provider down");
  }
}
