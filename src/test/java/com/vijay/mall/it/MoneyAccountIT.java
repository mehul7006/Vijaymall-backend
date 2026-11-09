package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.payment.domain.CardTerminal;
import com.vijay.mall.support.BankFakes;
import com.vijay.mall.support.FakeTerminal;
import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Money accounts, tagging of counter receipts, the till's card terminal sessions and the account book, against real PostgreSQL. */
@Import(BankFakes.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class MoneyAccountIT extends IntegrationTestBase {
  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry r) { r.add("app.payment.online-provider", () -> "fake"); }

  @Autowired FakeTerminal terminal;

  private static final String CASHIER = "cashier@vijaysupermall.local";

  private Map account(String admin, String name, String kind, Object settlesTo) {
    Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "kind", kind, "provider", "fake", "identifier", "1234"));
    if (settlesTo != null) body.put("settlesToAccountId", settlesTo);
    var res = call(HttpMethod.POST, "/api/v1/admin/money-accounts", admin, body, null);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private String uniq(String p) { return p + " " + UUID.randomUUID().toString().substring(0, 8); }

  private Map fields(org.springframework.http.ResponseEntity<Map> res) {
    return (Map) ((Map) res.getBody().get("details")).get("fields");
  }

  @Test void accountsAreAdminOnly() {
    assertThat(get("/api/v1/admin/money-accounts", token(CASHIER)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/money-accounts", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/v1/admin/settlements", token(CASHIER)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test void theCashDrawerIsSeeded_andCrudValidatesEveryField() {
    String admin = adminToken();
    var list = get("/api/v1/admin/money-accounts?kind=cash&active=true&q=drawer", admin).getBody();
    assertThat((List<Map>) list.get("items")).anySatisfy(a -> assertThat(a).containsEntry("name", "Cash drawer").containsEntry("kind", "CASH"));
    assertThat(get("/api/v1/admin/money-accounts?kind=SAFE", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/money-accounts?q=%25_", admin).getBody().get("items")).isEqualTo(List.of());

    Map bank = account(admin, uniq("HDFC"), "BANK", null);
    assertThat(bank).containsEntry("active", true).containsEntry("provider", "fake");
    Map gateway = account(admin, uniq("Gateway"), "gateway", bank.get("id"));
    assertThat(gateway).containsEntry("kind", "GATEWAY").containsEntry("settlesToAccountId", bank.get("id"));

    for (Map<String, Object> bad : List.of(
        Map.<String, Object>of("name", " ", "kind", "BANK"),
        Map.<String, Object>of("name", "x".repeat(81), "kind", "BANK"),
        Map.<String, Object>of("name", "ok", "kind", "VAULT"),
        Map.<String, Object>of("name", "ok", "kind", "BANK", "provider", "p".repeat(41)),
        Map.<String, Object>of("name", "ok", "kind", "BANK", "identifier", "i".repeat(61)),
        Map.<String, Object>of("name", "ok", "kind", "BANK", "note", "n".repeat(301)),
        Map.<String, Object>of("name", "ok", "kind", "BANK", "settlesToAccountId", bank.get("id")),
        Map.<String, Object>of("name", "ok", "kind", "GATEWAY", "settlesToAccountId", UUID.randomUUID().toString()),
        Map.<String, Object>of("name", "ok", "kind", "GATEWAY", "settlesToAccountId", gateway.get("id")))) {
      assertThat(call(HttpMethod.POST, "/api/v1/admin/money-accounts", admin, bad, null).getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    var dup = call(HttpMethod.POST, "/api/v1/admin/money-accounts", admin, Map.of("name", ((String) bank.get("name")).toUpperCase(), "kind", "BANK"), null);
    assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(dup.getBody()).containsEntry("code", "DUPLICATE_ACCOUNT_NAME");

    // repeat with the same key returns the first account
    Map body = Map.of("name", uniq("Keyed"), "kind", "UPI", "identifier", "shop@upi");
    String key = UUID.randomUUID().toString();
    var first = call(HttpMethod.POST, "/api/v1/admin/money-accounts", admin, body, key);
    var again = call(HttpMethod.POST, "/api/v1/admin/money-accounts", admin, body, key);
    assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));

    // update: unused accounts may change kind, an account cannot settle into itself, names stay unique
    Map upd = new java.util.HashMap<>(Map.of("name", uniq("Renamed"), "kind", "CARD_TERMINAL", "note", "machine 1"));
    var ok = call(HttpMethod.PUT, "/api/v1/admin/money-accounts/" + first.getBody().get("id"), admin, upd, null);
    assertThat(ok.getBody()).containsEntry("kind", "CARD_TERMINAL").containsEntry("note", "machine 1");
    upd.put("settlesToAccountId", first.getBody().get("id"));
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/money-accounts/" + first.getBody().get("id"), admin, upd, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    upd.remove("settlesToAccountId");
    upd.put("name", bank.get("name"));
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/money-accounts/" + first.getBody().get("id"), admin, upd, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(get("/api/v1/admin/money-accounts/" + UUID.randomUUID(), admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/money-accounts/" + bank.get("id"), admin).getBody()).containsEntry("name", bank.get("name"));
  }

  @Test void deactivateActivateAndDeleteFollowTheRules() {
    String admin = adminToken();
    Map bank = account(admin, uniq("Bank"), "BANK", null);
    Map gateway = account(admin, uniq("Gw"), "GATEWAY", bank.get("id"));
    String bankUrl = "/api/v1/admin/money-accounts/" + bank.get("id");
    String gwUrl = "/api/v1/admin/money-accounts/" + gateway.get("id");

    assertThat(post(bankUrl + "/deactivate", admin, null).getStatusCode()).as("an active gateway settles into it").isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.DELETE, bankUrl, admin, null, null).getStatusCode()).as("referenced by the gateway").isEqualTo(HttpStatus.CONFLICT);
    assertThat(post(gwUrl + "/deactivate", admin, null).getBody()).containsEntry("active", false);
    assertThat(post(gwUrl + "/deactivate", admin, null).getBody()).containsEntry("active", false); // twice is fine
    assertThat(post(gwUrl + "/activate", admin, null).getBody()).containsEntry("active", true);
    assertThat(post(gwUrl + "/activate", admin, null).getBody()).containsEntry("active", true);
    assertThat(call(HttpMethod.DELETE, gwUrl, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(call(HttpMethod.DELETE, bankUrl, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(get(bankUrl, admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    // the last active cash account stays
    Map extra = account(admin, uniq("Safe"), "CASH", null);
    assertThat(post("/api/v1/admin/money-accounts/" + extra.get("id") + "/deactivate", admin, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    String drawer = jdbc.queryForObject("select id::text from money_account where name = 'Cash drawer'", String.class);
    var last = post("/api/v1/admin/money-accounts/" + drawer + "/deactivate", admin, null);
    assertThat(last.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(last.getBody()).containsEntry("code", "LAST_CASH_ACCOUNT");

    // a used account keeps its kind
    Map used = account(admin, uniq("Used"), "UPI", null);
    jdbc.update("insert into money_account_entry (id, money_account_id, at, direction, amount_paise, note, created_at) values (?, ?::uuid, now(), 'IN', 5, '', now())",
        UUID.randomUUID(), used.get("id"));
    var kind = call(HttpMethod.PUT, "/api/v1/admin/money-accounts/" + used.get("id"), admin, Map.of("name", used.get("name"), "kind", "BANK"), null);
    assertThat(kind.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(kind.getBody()).containsEntry("code", "ACCOUNT_IN_USE");
  }

  @Test void handEnteredEntriesFormTheBookWithARunningBalance() {
    String admin = adminToken();
    Map bank = account(admin, uniq("Book"), "BANK", null);
    String id = (String) bank.get("id");
    String url = "/api/v1/admin/money-accounts/" + id + "/entries";
    var open = call(HttpMethod.POST, url, admin, Map.of("direction", "in", "amountPaise", 100000, "at", "2026-01-01T10:00:00Z", "note", "opening"), null);
    assertThat(open.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String key = UUID.randomUUID().toString();
    Map charge = Map.of("direction", "OUT", "amountPaise", 2500, "at", "2026-01-02T10:00:00Z", "note", "bank charge");
    var c1 = call(HttpMethod.POST, url, admin, charge, key);
    var c2 = call(HttpMethod.POST, url, admin, charge, key);
    assertThat(c2.getBody().get("id")).isEqualTo(c1.getBody().get("id"));
    call(HttpMethod.POST, url, admin, Map.of("direction", "IN", "amountPaise", 700, "note", "interest"), null); // now

    for (Map<String, Object> bad : List.of(Map.<String, Object>of("direction", "SIDEWAYS", "amountPaise", 5), Map.<String, Object>of("direction", "IN", "amountPaise", 0),
        Map.<String, Object>of("direction", "IN", "amountPaise", 5, "note", "n".repeat(201)))) {
      assertThat(call(HttpMethod.POST, url, admin, bad, null).getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    assertThat(call(HttpMethod.POST, "/api/v1/admin/money-accounts/" + UUID.randomUUID() + "/entries", admin, charge, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    Map book = get("/api/v1/admin/money-accounts/" + id + "/book?from=2026-01-02&to=2026-01-02", admin).getBody();
    assertThat(book).containsEntry("openingBalancePaise", 100000);
    List<Map> lines = (List<Map>) ((Map) book.get("lines")).get("items");
    assertThat(lines).singleElement().satisfies(l -> assertThat(l).containsEntry("direction", "OUT").containsEntry("balancePaise", 97500).containsEntry("sourceType", "MANUAL"));
    assertThat(get("/api/v1/admin/money-accounts/" + id + "/book?from=2026-03-01&to=2026-01-01", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/money-accounts/" + id + "/book?from=2024-01-01&to=2026-01-01", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/money-accounts/" + UUID.randomUUID() + "/book", admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/money-accounts/" + id + "/book", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  // ---------- counter tagging ----------
  private Object riceId() {
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", token(CASHIER)).getBody().get("items");
    return items.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst().orElseThrow().get("id");
  }

  private Map sale(String cashier, List<Map> payments, int qty) {
    var res = call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", qty))),
        "payments", payments), UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private org.springframework.http.ResponseEntity<Map> tryAccount(String cashier, String mode, Object account, long amount) {
    Map<String, Object> pay = new java.util.HashMap<>(Map.of("mode", mode, "amountPaise", amount));
    if (account != null) pay.put("moneyAccountId", account);
    return call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1))),
        "payments", List.of(pay)), UUID.randomUUID().toString());
  }

  @Test void counterPaymentsAreTaggedAndRefundsFollowTheirAccount() {
    String admin = adminToken();
    String cashier = token(CASHIER);
    restock("Basmati Rice", 50);
    Map upi = account(admin, uniq("QR"), "UPI", null);
    Map card = account(admin, uniq("Machine"), "CARD_TERMINAL", null);
    Map bank = account(admin, uniq("Bank"), "BANK", null);
    String drawer = jdbc.queryForObject("select id::text from money_account where name = 'Cash drawer'", String.class);

    // an old-style payload without any account: cash goes to the drawer, UPI has no default yet
    Map plain = sale(cashier, List.of(Map.of("mode", "CASH", "amountPaise", 12500)), 1);
    assertThat(jdbc.queryForObject("select money_account_id::text from pos_payment where sale_id = ?::uuid", String.class, plain.get("id"))).isEqualTo(drawer);
    Map noDefault = sale(cashier, List.of(Map.of("mode", "UPI", "amountPaise", 12500, "reference", "UTR-ND")), 1);
    assertThat(jdbc.queryForObject("select money_account_id from pos_payment where sale_id = ?::uuid", String.class, noDefault.get("id"))).isNull();

    // defaults come from the settings; a bad id in a setting is ignored
    var set = call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(Map.of("key", "pos.defaultAccount.UPI", "value", upi.get("id")),
        Map.of("key", "pos.defaultAccount.CARD", "value", "not-a-uuid"))), null);
    assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map viaDefault = sale(cashier, List.of(Map.of("mode", "UPI", "amountPaise", 12500, "reference", "UTR-D")), 1);
    assertThat(jdbc.queryForObject("select money_account_id::text from pos_payment where sale_id = ?::uuid", String.class, viaDefault.get("id"))).isEqualTo(upi.get("id"));
    Map badDefault = sale(cashier, List.of(Map.of("mode", "CARD", "amountPaise", 12500)), 1);
    assertThat(jdbc.queryForObject("select money_account_id from pos_payment where sale_id = ?::uuid", String.class, badDefault.get("id"))).isNull();

    // explicit account, reference and terminal id on a split bill
    Map split = sale(cashier, List.of(Map.of("mode", "CARD", "amountPaise", 12500, "reference", "APPR-1", "moneyAccountId", card.get("id"), "terminalId", "T-9"),
        Map.of("mode", "UPI", "amountPaise", 12500, "moneyAccountId", bank.get("id"))), 2);
    Map row = jdbc.queryForMap("select money_account_id::text acc, terminal_id, reference from pos_payment where sale_id = ?::uuid and mode = 'CARD'", split.get("id"));
    assertThat(row).containsEntry("acc", card.get("id")).containsEntry("terminal_id", "T-9").containsEntry("reference", "APPR-1");

    // checks: unknown, wrong kind, switched off, credit with an account
    assertThat(tryAccount(cashier, "UPI", UUID.randomUUID().toString(), 12500).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var wrong = tryAccount(cashier, "CASH", upi.get("id"), 12500);
    assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fields(wrong)).containsKey("payments[0].moneyAccountId");
    assertThat(tryAccount(cashier, "CARD", upi.get("id"), 12500).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    post("/api/v1/admin/money-accounts/" + card.get("id") + "/deactivate", admin, null);
    assertThat(tryAccount(cashier, "CARD", card.get("id"), 12500).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    post("/api/v1/admin/money-accounts/" + card.get("id") + "/activate", admin, null);
    assertThat(tryAccount(cashier, "CREDIT", upi.get("id"), 12500).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    // a return follows the account of the bill's line of the same mode, or the one named
    List<Map> lines = (List<Map>) split.get("items");
    var ret = call(HttpMethod.POST, "/api/v1/pos/sales/" + split.get("id") + "/returns", cashier,
        Map.of("items", List.of(Map.of("saleItemId", lines.get(0).get("id"), "qty", 1)), "refundMode", "CARD"), UUID.randomUUID().toString());
    assertThat(ret.getStatusCode()).as(String.valueOf(ret.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(jdbc.queryForObject("select money_account_id::text from pos_refund_payment where return_id = ?::uuid", String.class, ret.getBody().get("id")))
        .isEqualTo(card.get("id"));
    var named = call(HttpMethod.POST, "/api/v1/pos/sales/" + split.get("id") + "/returns", cashier, Map.of("items", List.of(Map.of("saleItemId", lines.get(0).get("id"), "qty", 1)),
        "refunds", List.of(Map.of("mode", "UPI", "amountPaise", 12500, "moneyAccountId", upi.get("id")))), UUID.randomUUID().toString());
    assertThat(named.getStatusCode()).as(String.valueOf(named.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(jdbc.queryForObject("select money_account_id::text from pos_refund_payment where return_id = ?::uuid", String.class, named.getBody().get("id")))
        .isEqualTo(upi.get("id"));
    // a cash return of a bill with no cash line uses the drawer
    Map cashBill = sale(cashier, List.of(Map.of("mode", "UPI", "amountPaise", 12500, "moneyAccountId", bank.get("id"))), 1);
    var cashBack = call(HttpMethod.POST, "/api/v1/pos/sales/" + cashBill.get("id") + "/returns", cashier,
        Map.of("items", List.of(Map.of("saleItemId", ((List<Map>) cashBill.get("items")).get(0).get("id"), "qty", 1)), "refundMode", "CASH"), UUID.randomUUID().toString());
    assertThat(jdbc.queryForObject("select money_account_id::text from pos_refund_payment where return_id = ?::uuid", String.class, cashBack.getBody().get("id"))).isEqualTo(drawer);

    // the receipts report shows them per account, with an Unassigned bucket
    Map report = get("/api/v1/admin/bank-reports/receipts?size=50", admin).getBody();
    List<Map> items = (List<Map>) report.get("items");
    assertThat(items).anySatisfy(r -> assertThat(r).containsEntry("accountName", "Unassigned").containsEntry("moneyAccountId", null));
    assertThat(items).anySatisfy(r -> assertThat(r).containsEntry("moneyAccountId", card.get("id")).containsEntry("method", "CARD"));
    assertThat(get("/api/v1/admin/money-accounts/" + card.get("id") + "/book", admin).getBody().toString()).contains("COUNTER_PAYMENT", "COUNTER_REFUND");
  }

  // ---------- card terminal ----------
  private void useTerminal(String name) {
    call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(Map.of("key", "pos.terminalProvider", "value", name))), null);
  }

  @Test void manualTerminalSessionIsConfirmedByTheCashier() {
    useTerminal("manual");
    String cashier = token(CASHIER);
    String url = "/api/v1/pos/terminal/payments";
    String ref = "BILL-" + UUID.randomUUID();
    var start = call(HttpMethod.POST, url, cashier, Map.of("amountPaise", 12345, "reference", ref), UUID.randomUUID().toString());
    assertThat(start.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(start.getBody()).containsEntry("state", "PENDING").containsEntry("mode", "MANUAL").containsEntry("provider", "manual");
    assertThat((String) start.getBody().get("instructions")).contains("123.45");
    String id = (String) start.getBody().get("id");

    // the same reference and amount is the same session; another amount is refused
    assertThat(post(url, cashier, Map.of("amountPaise", 12345, "reference", ref)).getBody().get("id")).isEqualTo(id);
    assertThat(post(url, cashier, Map.of("amountPaise", 999, "reference", ref)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    String key = UUID.randomUUID().toString();
    var k1 = call(HttpMethod.POST, url, cashier, Map.of("amountPaise", 500), key);
    assertThat(call(HttpMethod.POST, url, cashier, Map.of("amountPaise", 500), key).getBody().get("id")).isEqualTo(k1.getBody().get("id"));

    assertThat(get(url + "/" + id, cashier).getBody()).containsEntry("state", "PENDING");
    assertThat(post(url + "/" + id + "/confirm", cashier, Map.of("approvalCode", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(url + "/" + id + "/confirm", cashier, Map.of("approvalCode", "A".repeat(41))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(url + "/" + id + "/confirm", cashier, Map.of("approvalCode", "OK1", "rrn", "R".repeat(41))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var done = post(url + "/" + id + "/confirm", cashier, Map.of("approvalCode", "OK123", "rrn", "RRN9"));
    assertThat(done.getBody()).containsEntry("state", "APPROVED").containsEntry("approvalCode", "OK123").containsEntry("rrn", "RRN9");
    assertThat(post(url + "/" + id + "/confirm", cashier, Map.of("approvalCode", "OK123")).getBody()).containsEntry("state", "APPROVED");
    assertThat(post(url + "/" + id + "/cancel", cashier, null).getBody()).containsEntry("state", "APPROVED");

    String id2 = (String) k1.getBody().get("id");
    assertThat(post(url + "/" + id2 + "/cancel", cashier, null).getBody()).containsEntry("state", "CANCELLED");
    var closed = post(url + "/" + id2 + "/confirm", cashier, Map.of("approvalCode", "X"));
    assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(closed.getBody()).containsEntry("code", "TERMINAL_PAYMENT_CLOSED");
    assertThat(get(url + "/" + id2, cashier).getBody()).containsEntry("state", "CANCELLED");

    for (Map<String, Object> bad : List.of(Map.<String, Object>of("amountPaise", 0), Map.<String, Object>of("amountPaise", 100_000_001),
        Map.<String, Object>of("amountPaise", 5, "reference", "r".repeat(61)))) {
      assertThat(post(url, cashier, bad).getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    assertThat(get(url + "/" + UUID.randomUUID(), cashier).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post(url + "/" + UUID.randomUUID() + "/confirm", cashier, Map.of("approvalCode", "X")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post(url + "/" + UUID.randomUUID() + "/cancel", cashier, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post(url, token(freshEmail()), Map.of("amountPaise", 5)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test void aTerminalThatReportsItselfIsPolled() {
    useTerminal("faketerm");
    try {
      String cashier = token(CASHIER);
      String url = "/api/v1/pos/terminal/payments";
      var start = post(url, cashier, Map.of("amountPaise", 5000));
      assertThat(start.getBody()).containsEntry("mode", "QR").containsEntry("qrPayload", "upi://pay?am=5000");
      String id = (String) start.getBody().get("id");
      String ref = (String) start.getBody().get("reference");
      assertThat(ref).startsWith("TP-");
      assertThat(get(url + "/" + id, cashier).getBody()).containsEntry("state", "PENDING");
      assertThat(post(url + "/" + id + "/confirm", cashier, Map.of("approvalCode", "X")).getBody()).containsEntry("code", "TERMINAL_CONFIRMS_ITSELF");
      terminal.finish(ref, CardTerminal.State.APPROVED, "APP7", "RRN7");
      assertThat(get(url + "/" + id, cashier).getBody()).containsEntry("state", "APPROVED").containsEntry("approvalCode", "APP7").containsEntry("rrn", "RRN7");

      var second = post(url, cashier, Map.of("amountPaise", 700)).getBody();
      assertThat(post(url + "/" + second.get("id") + "/cancel", cashier, null).getBody()).containsEntry("state", "CANCELLED");

      terminal.configured = false;
      var off = post(url, cashier, Map.of("amountPaise", 5000));
      assertThat(off.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(off.getBody()).containsEntry("code", "TERMINAL_NOT_CONFIGURED");
    } finally {
      terminal.configured = true;
      useTerminal("manual");
    }
    useTerminal("nosuchbrand");
    assertThat(post("/api/v1/pos/terminal/payments", token(CASHIER), Map.of("amountPaise", 5000)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    useTerminal("manual");
  }

  @Test void theTillListsTheActiveChannelsAndTheAdminPicksTheOnlineGateway() {
    String admin = adminToken();
    Map upi = account(admin, uniq("PhonePe QR"), "UPI", null);
    Map term = new java.util.HashMap<>(account(admin, uniq("Swipe"), "CARD_TERMINAL", null));
    call(HttpMethod.PUT, "/api/v1/admin/money-accounts/" + term.get("id"), admin, Map.of("name", term.get("name"), "kind", "CARD_TERMINAL", "provider", "paytm", "identifier", "TID-77"), null);
    call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(Map.of("key", "pos.defaultAccount.CARD", "value", term.get("id")))), null);
    List<Map> channels = (List<Map>) get("/api/v1/pos/payment-channels", token(CASHIER)).getBody().get("_array");
    assertThat(channels).anySatisfy(c -> assertThat(c).containsEntry("mode", "CARD").containsEntry("provider", "paytm").containsEntry("terminalId", "TID-77")
        .containsEntry("moneyAccountId", term.get("id")).containsEntry("default", true));
    assertThat(channels).anySatisfy(c -> assertThat(c).containsEntry("moneyAccountId", upi.get("id")).containsEntry("default", false));
    assertThat(channels).anySatisfy(c -> assertThat(c).containsEntry("mode", "CASH").containsEntry("name", "Cash drawer"));
    assertThat(get("/api/v1/pos/payment-channels", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    for (String choice : List.of("fake", "nosuchgateway")) {
      assertThat(call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(Map.of("key", "payment.onlineProvider", "value", choice))), null).getStatusCode())
          .isEqualTo(HttpStatus.OK);
    }
    call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(Map.of("key", "payment.onlineProvider", "value", ""),
        Map.of("key", "pos.defaultAccount.CARD", "value", ""))), null);
  }
}
