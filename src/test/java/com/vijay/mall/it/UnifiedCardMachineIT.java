package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.payment.app.SettlementService;
import com.vijay.mall.payment.domain.SettlementProvider.Detail;
import com.vijay.mall.payment.domain.SettlementProvider.Item;
import com.vijay.mall.payment.domain.SettlementProvider.ItemKind;
import com.vijay.mall.payment.domain.SettlementProvider.Summary;
import com.vijay.mall.payment.paytm.PaytmFakeServer;
import com.vijay.mall.payment.paytm.PaytmFakeServer.Script;
import com.vijay.mall.payment.paytm.app.TerminalPaymentService;
import com.vijay.mall.support.BankFakes;
import com.vijay.mall.support.FakeSettlementProvider;
import com.vijay.mall.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * One card-machine flow for every vendor, end to end against the in-JVM Paytm fake: the till lists the channels, picks the Paytm machine, starts
 * through the generic endpoint, polls to paid, rings the bill with that one session id, and the receipts, the payer view and the imported Paytm
 * settlement all show it under vendor paytm. The manual channel keeps working beside it, a session survives a restart, and a vendor that fails to
 * sync does not stop another.
 */
@Import({BankFakes.class, UnifiedCardMachineIT.SecondProvider.class})
@SuppressWarnings({"unchecked", "rawtypes"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnifiedCardMachineIT extends IntegrationTestBase {
  /** A second healthy settlement source, next to the {@code fake} one of {@link BankFakes}. */
  @TestConfiguration
  static class SecondProvider {
    @Bean SecondFake secondFake() { return new SecondFake(); }
  }

  /** Same behaviour as the fake provider under another name. */
  static final class SecondFake extends FakeSettlementProvider {
    @Override public String provider() { return "fake2"; }
  }

  private static final PaytmFakeServer FAKE = new PaytmFakeServer();
  private static final String CASHIER = "cashier@vijaysupermall.local";
  private static final String TERMINAL_PAYMENTS = "/api/v1/pos/terminal/payments";
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  @Autowired private TerminalPaymentService paytmPayments;
  @Autowired private SettlementService settlements;
  @Autowired @Qualifier("fakeSettlementProvider") private FakeSettlementProvider fake;
  @Autowired private SecondFake fake2;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) { r.add("app.paytm.starts-per-minute", () -> 1000); }

  private String admin;
  private String cashier;
  private Map bank;

  private static Map change(String key, String value) { return Map.of("key", key, "value", value); }

  @BeforeAll
  void clean() {
    admin = adminToken();
    cashier = token(CASHIER);
    jdbc.update("update payment_terminal set active = false");
    jdbc.update("update money_account set active = false where kind = 'CARD_TERMINAL'");
  }

  @AfterAll
  void tearDown() {
    FAKE.close();
    call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.enabled", "false"), change("paytm.mid", ""), change("paytm.merchantKey", ""),
        change("paytm.channelId", ""), change("paytm.baseUrl", ""), change("paytm.pollSeconds", ""), change("pos.terminalProvider", "manual"))), null);
    jdbc.update("update payment_terminal set active = false");
    jdbc.update("update money_account set active = false where kind = 'CARD_TERMINAL'");
  }

  @BeforeEach
  void setUp() {
    var res = call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(change("paytm.enabled", "true"), change("paytm.baseUrl", FAKE.baseUrl()),
        change("paytm.mid", "TESTMID123"), change("paytm.merchantKey", PaytmFakeServer.KEY), change("paytm.channelId", "EDC"), change("paytm.pollSeconds", "60"),
        change("pos.terminalProvider", "manual"))), null);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
    FAKE.voidCode = "0009";
    FAKE.fallback(Script.CARD_SUCCESS);
    fake.reset();
    fake2.reset();
    restock("Basmati Rice", 200);
    if (bank == null) bank = account("Unified Bank", "BANK", "manual", "", null);
  }

  // ---------- helpers ----------
  private static String uniq(String s) { return s + " " + UUID.randomUUID().toString().substring(0, 8); }

  private static String tid() { return UUID.randomUUID().toString().replace("-", "").substring(0, 8); }

  private Map account(String name, String kind, String provider, String identifier, Object settlesTo) {
    Map<String, Object> body = new HashMap<>(Map.of("name", uniq(name), "kind", kind, "provider", provider, "identifier", identifier));
    if (settlesTo != null) body.put("settlesToAccountId", settlesTo);
    var res = post("/api/v1/admin/money-accounts", admin, body);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private Map machine(String name) {
    var res = post("/api/v1/admin/payment-terminals", admin, Map.of("terminalId", tid(), "name", uniq(name), "settlesToAccountId", bank.get("id")));
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private Object riceId() {
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", cashier).getBody().get("items");
    return items.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst().orElseThrow().get("id");
  }

  private ResponseEntity<Map> sell(List<Map> payments) {
    return call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1))), "payments", payments),
        UUID.randomUUID().toString());
  }

  private Map sellOk(List<Map> payments) {
    var res = sell(payments);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private ResponseEntity<Map> start(Object account, long amount, String reference) {
    Map<String, Object> body = new HashMap<>(Map.of("amountPaise", amount, "reference", reference));
    if (account != null) body.put("moneyAccountId", account);
    return call(HttpMethod.POST, TERMINAL_PAYMENTS, cashier, body, UUID.randomUUID().toString());
  }

  private Map startOk(Object account, long amount, String reference) {
    var res = start(account, amount, reference);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private Map poll(Object id) {
    jdbc.update("update terminal_payment set next_poll_at = now() - interval '1 second' where id = ?::uuid and next_poll_at is not null", id);
    var res = get(TERMINAL_PAYMENTS + "/" + id, cashier);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    return res.getBody();
  }

  private Map pollUntil(Object id, String state) {
    Map m = null;
    for (int i = 0; i < 6; i++) {
      m = poll(id);
      if (state.equals(m.get("state"))) return m;
    }
    throw new AssertionError("never reached " + state + ": " + m);
  }

  private List<Map> receipts(String query) {
    return (List<Map>) get("/api/v1/admin/receipts?size=100&" + query, admin).getBody().get("items");
  }

  private ResponseEntity<Map> upload(String csv, Object accountId, Map<String, String> extra) {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
      @Override public String getFilename() { return "paytm-settlement.csv"; }
    });
    parts.add("moneyAccountId", accountId.toString());
    extra.forEach(parts::add);
    var res = client().post().uri("/api/v1/admin/settlements/import").headers(h -> h.setBearerAuth(admin)).contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
        .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    Map body = res.getBody() == null ? null : JsonMapper.builder().build().readValue(res.getBody(), Map.class);
    return new ResponseEntity<>(body, res.getHeaders(), res.getStatusCode());
  }

  private static final String HEADER = "Transaction ID,Order ID,Transaction Date,Updated date,Transaction Type,Status,Amount,Commission,GST,Settled amount,Settled Date,UTR no.,Split Flag,Payment Mode\n";

  private static String today() { return LocalDate.now(INDIA).toString(); }

  private static String row(String txn, String order, String amount, String fee, String gst, String settled, String utr, String mode) {
    return txn + "," + order + "," + today() + " 10:00:00,,ACQUIRING,SUCCESS," + amount + "," + fee + "," + gst + "," + settled + "," + today() + "," + utr + ",N," + mode + "\n";
  }

  // ---------- accounts follow the machines ----------
  @Test
  void everyMachineHasItsOwnAccountThatFollowsItAndShowsAsAChannel() {
    Map m = machine("Counter A");
    assertThat(m.get("moneyAccountId")).isNotNull();
    assertThat(m).containsEntry("settlesToAccountId", bank.get("id"));
    Map acc = get("/api/v1/admin/money-accounts/" + m.get("moneyAccountId"), admin).getBody();
    assertThat(acc).containsEntry("kind", "CARD_TERMINAL").containsEntry("provider", "paytm").containsEntry("identifier", m.get("terminalId"))
        .containsEntry("name", m.get("name")).containsEntry("settlesToAccountId", bank.get("id")).containsEntry("active", true);

    List<Map> channels = (List<Map>) get("/api/v1/pos/payment-channels", cashier).getBody().get("_array");
    assertThat(channels).anySatisfy(c -> assertThat(c).containsEntry("mode", "CARD").containsEntry("provider", "paytm").containsEntry("terminalId", m.get("terminalId"))
        .containsEntry("moneyAccountId", m.get("moneyAccountId")).containsKey("default"));
    List<Map> till = (List<Map>) get("/api/v1/pos/terminals", cashier).getBody().get("_array");
    assertThat(till).anySatisfy(t -> assertThat(t).containsEntry("moneyAccountId", m.get("moneyAccountId")));

    // an invalid bank is refused with the field named
    var bad = call(HttpMethod.PUT, "/api/v1/admin/payment-terminals/" + m.get("id"), admin, Map.of("name", m.get("name"), "settlesToAccountId", UUID.randomUUID()), null);
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat((Map) ((Map) bad.getBody().get("details")).get("fields")).containsKey("settlesToAccountId");

    // renaming moves the account's name; a clash with another account gets the terminal id appended
    Map other = account("Taken name", "UPI", "paytm", "", null);
    var clash = call(HttpMethod.PUT, "/api/v1/admin/payment-terminals/" + m.get("id"), admin, Map.of("name", other.get("name")), null);
    assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/admin/money-accounts/" + m.get("moneyAccountId"), admin).getBody().get("name")).isEqualTo(other.get("name") + " (" + m.get("terminalId") + ")");
    assertThat(((Map) clash.getBody()).get("settlesToAccountId")).as("a PUT without a bank clears it").isNull();

    // switching the machine off switches its account off; switching the account on again brings the machine back
    var off = post("/api/v1/admin/payment-terminals/" + m.get("id") + "/deactivate", admin, null);
    assertThat(off.getBody()).containsEntry("active", false);
    assertThat(get("/api/v1/admin/money-accounts/" + m.get("moneyAccountId"), admin).getBody()).containsEntry("active", false);
    assertThat((List<Map>) get("/api/v1/pos/payment-channels", cashier).getBody().get("_array")).noneMatch(c -> m.get("moneyAccountId").equals(c.get("moneyAccountId")));
    assertThat(start(m.get("moneyAccountId"), 1000, "OFF-" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    post("/api/v1/admin/money-accounts/" + m.get("moneyAccountId") + "/activate", admin, null);
    assertThat(jdbc.queryForObject("select active from payment_terminal where id = ?::uuid", Boolean.class, m.get("id"))).isTrue();
    post("/api/v1/admin/money-accounts/" + m.get("moneyAccountId") + "/deactivate", admin, null);
    assertThat(jdbc.queryForObject("select active from payment_terminal where id = ?::uuid", Boolean.class, m.get("id"))).isFalse();
  }

  @Test
  void theBackfillLinksMachinesThatHadNoAccountAndMovesAnOldBankPointer() throws Exception {
    UUID plain = UUID.randomUUID();
    UUID legacy = UUID.randomUUID();
    String plainTid = tid();
    String legacyTid = tid();
    String name = uniq("Old machine");
    jdbc.update("insert into money_account (id, name, kind, provider, identifier, active, note, created_at, updated_at) values (?, ?, 'UPI', 'manual', '', true, '', now(), now())",
        UUID.randomUUID(), name);
    jdbc.update("insert into payment_terminal (id, provider, terminal_id, name, active, created_at, updated_at) values (?, 'paytm', ?, ?, true, now(), now())", plain, plainTid, name);
    jdbc.update("insert into payment_terminal (id, provider, terminal_id, name, active, money_account_id, created_at, updated_at) values (?, 'paytm', ?, ?, false, ?::uuid, now(), now())",
        legacy, legacyTid, uniq("Legacy"), bank.get("id"));
    String sql = new String(new ClassPathResource("db/migration/V121__paytm_terminal_money_accounts.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    jdbc.execute(sql);
    jdbc.execute(sql);
    Map a = jdbc.queryForMap("select a.id::text id, a.name, a.kind, a.provider, a.identifier, a.active, a.settles_to_account_id::text bank from payment_terminal t "
        + "join money_account a on a.id = t.money_account_id where t.id = ?", plain);
    assertThat(a).containsEntry("kind", "CARD_TERMINAL").containsEntry("provider", "paytm").containsEntry("identifier", plainTid).containsEntry("active", true)
        .containsEntry("name", name + " (" + plainTid + ")");
    Map l = jdbc.queryForMap("select a.kind, a.active, a.settles_to_account_id::text bank from payment_terminal t join money_account a on a.id = t.money_account_id where t.id = ?", legacy);
    assertThat(l).containsEntry("kind", "CARD_TERMINAL").containsEntry("active", false).containsEntry("bank", bank.get("id"));
    assertThat(jdbc.queryForObject("select count(*) from money_account where kind = 'CARD_TERMINAL' and identifier in (?, ?)", Integer.class, plainTid, legacyTid)).isEqualTo(2);
    jdbc.update("update payment_terminal set active = false where id in (?, ?)", plain, legacy);
    jdbc.update("update money_account set active = false where kind = 'CARD_TERMINAL'");
  }

  // ---------- the full journey ----------
  @Test
  void theTillJourneyOnAPaytmMachineFromChannelToSettlement() {
    Map m = machine("Journey");
    Map manual = account("Manual machine", "CARD_TERMINAL", "manual", "", null);
    Map gateway = account("Razorpay online", "GATEWAY", "razorpay", "", bank.get("id"));
    String acc = (String) m.get("moneyAccountId");

    // 1. list channels: several vendors side by side
    List<Map> channels = (List<Map>) get("/api/v1/pos/payment-channels", cashier).getBody().get("_array");
    assertThat(channels).anySatisfy(c -> assertThat(c).containsEntry("moneyAccountId", acc).containsEntry("provider", "paytm").containsEntry("terminalId", m.get("terminalId")));
    assertThat(channels).anySatisfy(c -> assertThat(c).containsEntry("moneyAccountId", manual.get("id")).containsEntry("provider", "manual"));
    assertThat(channels).anySatisfy(c -> assertThat(c).containsEntry("moneyAccountId", gateway.get("id")).containsEntry("provider", "razorpay"));

    // 2. choose the Paytm machine and start through the generic endpoint
    String ref = "INV-" + UUID.randomUUID().toString().substring(0, 8);
    Map started = startOk(acc, 12500, ref);
    String id = (String) started.get("id");
    assertThat(started).containsEntry("provider", "paytm").containsEntry("state", "PENDING").containsEntry("providerStatus", "PENDING").containsEntry("mode", "PUSH")
        .containsEntry("open", true).containsEntry("moneyAccountId", acc).containsEntry("terminalId", m.get("terminalId")).containsEntry("terminalName", m.get("name"))
        .containsEntry("reference", ref).containsEntry("canCancel", true).containsEntry("cancelOnMachine", false);
    assertThat(jdbc.queryForObject("select count(*) from card_terminal_session where reference = ?", Integer.class, ref)).as("no second table of truth").isZero();
    assertThat(jdbc.queryForObject("select count(*) from terminal_payment where id = ?::uuid", Integer.class, id)).isEqualTo(1);
    // the same reference is the same payment; the Paytm-specific endpoint reads the very same row
    assertThat(startOk(acc, 12500, ref).get("id")).isEqualTo(id);
    assertThat(call(HttpMethod.POST, TERMINAL_PAYMENTS, cashier, Map.of("amountPaise", 999, "reference", ref, "moneyAccountId", acc), UUID.randomUUID().toString()).getBody())
        .containsEntry("code", "REFERENCE_REUSED");
    assertThat(get("/api/v1/pos/terminal-payments/" + id, cashier).getBody()).containsEntry("id", id).containsEntry("status", "PENDING");

    // one open payment per machine; another machine and another vendor stay usable
    var busy = start(acc, 3000, "BUSY-" + UUID.randomUUID());
    assertThat(busy.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(busy.getBody()).containsEntry("code", "TERMINAL_BUSY");
    assertThat((Map) busy.getBody().get("details")).containsEntry("openPaymentId", id);
    Map second = machine("Second");
    String otherId = (String) startOk(second.get("moneyAccountId"), 4000, "OTHER-" + UUID.randomUUID()).get("id");
    assertThat(startOk(manual.get("id"), 5000, "MAN-" + UUID.randomUUID())).containsEntry("provider", "manual");

    // 3. poll to paid
    Map paid = pollUntil(id, "APPROVED");
    assertThat(paid).containsEntry("providerStatus", "SUCCESS").containsEntry("open", false).containsEntry("rrn", "412345678901").containsEntry("approvalCode", "A1B2C3")
        .containsEntry("payMethod", "CREDIT_CARD").containsEntry("cardScheme", "VISA").containsEntry("maskedCard", "411111XXXXXX1111").containsEntry("issuingBank", "HDFC Bank")
        .containsEntry("canVoid", true).containsEntry("canCancel", false);
    assertThat(paid.toString()).doesNotContain("rawResponse");

    // 4. the bill carries the one session id
    Map sale = sellOk(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    String saleId = (String) sale.get("id");
    Map line = jdbc.queryForMap("select id::text id, reference, money_account_id::text acc, terminal_id, terminal_payment_id::text tp, detail::text detail from pos_payment where sale_id = ?::uuid", saleId);
    assertThat(line).containsEntry("reference", "412345678901").containsEntry("acc", acc).containsEntry("terminal_id", m.get("terminalId")).containsEntry("tp", id);
    assertThat((String) line.get("detail")).contains("\"cardLast4\"").contains("1111").contains("VISA").contains("HDFC Bank").contains("A1B2C3").contains("\"payMethod\"");
    assertThat(get(TERMINAL_PAYMENTS + "/" + id, cashier).getBody()).containsEntry("posSaleId", saleId).containsEntry("canVoid", false);
    assertThat(call(HttpMethod.POST, "/api/v1/pos/sales", cashier, Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1))),
        "payments", List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id))), UUID.randomUUID().toString()).getBody()).containsEntry("code", "TERMINAL_PAYMENT_USED");
    // naming another machine's account on the line is refused
    pollUntil(otherId, "APPROVED");
    var mismatch = sell(List.of(Map.of("mode", "CARD", "amountPaise", 4000, "terminalPaymentId", otherId, "moneyAccountId", acc), Map.of("mode", "CASH", "amountPaise", 8500)));
    assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat((Map) ((Map) mismatch.getBody().get("details")).get("fields")).containsKey("payments[0].moneyAccountId");

    // 5. receipts and the payer view show vendor paytm, the account, the method and the masked payer
    List<Map> lines = receipts("provider=paytm&method=CARD&account=" + acc);
    assertThat(lines).singleElement().satisfies(l -> assertThat(l).containsEntry("source", "POS_SALE").containsEntry("provider", "paytm").containsEntry("moneyAccountId", acc)
        .containsEntry("accountName", m.get("name")).containsEntry("method", "CARD").containsEntry("terminalId", m.get("terminalId")).containsEntry("payer", "VISA ****1111")
        .containsEntry("reference", "412345678901").containsEntry("grossPaise", 12500).containsEntry("documentNo", sale.get("invoiceNo")));
    List<Map> summary = (List<Map>) get("/api/v1/admin/receipts/summary", admin).getBody().get("_array");
    assertThat(summary).anySatisfy(t -> assertThat(t).containsEntry("provider", "paytm").containsEntry("moneyAccountId", acc).containsEntry("method", "CARD"));
    List<Map> payers = (List<Map>) get("/api/v1/admin/payments/by-sale/" + saleId, admin).getBody().get("_array");
    assertThat(payers).singleElement().satisfies(p -> assertThat(p).containsEntry("provider", "paytm").containsEntry("cardScheme", "VISA").containsEntry("cardLast4", "1111")
        .containsEntry("issuingBank", "HDFC Bank").containsEntry("accountName", m.get("name")).containsEntry("reference", "412345678901").containsEntry("terminalId", m.get("terminalId")));
    assertThat(payers.toString()).doesNotContain("411111XXXXXX1111");

    // 6. a refund of the machine payment follows the payment's account
    Map item = ((List<Map>) sale.get("items")).get(0);
    var ret = call(HttpMethod.POST, "/api/v1/pos/sales/" + saleId + "/returns", cashier, Map.of("items", List.of(Map.of("saleItemId", item.get("id"), "qty", 1)), "refundMode", "CARD"),
        UUID.randomUUID().toString());
    assertThat(ret.getStatusCode()).as(String.valueOf(ret.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(jdbc.queryForObject("select money_account_id::text from pos_refund_payment where return_id = ?::uuid", String.class, ret.getBody().get("id"))).isEqualTo(acc);

    // 7. import the Paytm Settlement Report: format found by the header
    String orderId = id.replace("-", "");
    String stray = "STRAY" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String csv = HEADER + row("TX" + orderId.substring(0, 10), orderId, "125.00", "1.00", "0.18", "123.82", "UTRP1", "Credit Card")
        + row("TX" + stray, stray, "55.00", "0.50", "0.09", "54.41", "UTRP1", "Credit Card");
    var imported = upload(csv, acc, Map.of());
    assertThat(imported.getStatusCode()).as(String.valueOf(imported.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(imported.getBody()).containsEntry("rows", 2).containsEntry("matched", 1).containsEntry("unmatched", 1).containsEntry("alreadyImported", false).containsEntry("netPaise", 17823);
    String settlementId = (String) imported.getBody().get("settlementId");
    Map st = get("/api/v1/admin/settlements/" + settlementId, admin).getBody();
    assertThat(st).containsEntry("provider", "paytm").containsEntry("utr", "UTRP1").containsEntry("grossPaise", 18000).containsEntry("feePaise", 150).containsEntry("taxPaise", 27)
        .containsEntry("netPaise", 17823).containsEntry("source", "CSV").containsEntry("moneyAccountId", acc).containsEntry("items", 2).containsEntry("unmatchedItems", 1);
    List<Map> items = (List<Map>) get("/api/v1/admin/settlements/" + settlementId + "/items?size=50", admin).getBody().get("items");
    assertThat(items).filteredOn(i -> Boolean.TRUE.equals(i.get("matched"))).singleElement().satisfies(i -> assertThat(i).containsEntry("invoiceNo", sale.get("invoiceNo"))
        .containsEntry("feePaise", 100).containsEntry("taxPaise", 18).containsEntry("netPaise", 12382).containsEntry("utr", "UTRP1").containsEntry("method", "card"));
    assertThat(jdbc.queryForObject("select detail ->> 'transactionType' from settlement_item where settlement_id = ?::uuid and matched", String.class, settlementId)).isEqualTo("ACQUIRING");
    List<Map> open = (List<Map>) get("/api/v1/admin/settlements/exceptions?status=OPEN&kind=UNMATCHED_ITEM&size=100", admin).getBody().get("items");
    assertThat(open).anySatisfy(e -> assertThat(e).containsEntry("reference", stray).containsEntry("actualPaise", 5500).containsEntry("settlementId", settlementId));
    // the same file again changes nothing
    assertThat(upload(csv, acc, Map.of()).getBody()).containsEntry("alreadyImported", true);
    // the receipt line now carries the payout
    assertThat(receipts("provider=paytm&account=" + acc)).anySatisfy(l -> assertThat(l).containsEntry("utr", "UTRP1").containsEntry("feePaise", 100).containsEntry("taxPaise", 18)
        .containsEntry("netPaise", 12382).containsEntry("reference", "412345678901"));
    assertThat(((Map) ((List<Map>) get("/api/v1/admin/payments/by-sale/" + saleId, admin).getBody().get("_array")).get(0))).containsEntry("utr", "UTRP1").containsEntry("netPaise", 12382);
  }

  @Test
  void aPaytmReportMatchesByRrnAndByStickerAmountAndDayOrRaisesExceptions() {
    Map m = machine("Report");
    String acc = (String) m.get("moneyAccountId");
    Map qr = account("Paytm QR sticker", "UPI", "paytm", "", bank.get("id"));
    String id = (String) startOk(acc, 12500, "RPT-" + UUID.randomUUID()).get("id");
    pollUntil(id, "APPROVED");
    sellOk(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    // a sticker payment of Rs 50 (unique amount) and two of Rs 30 (ambiguous)
    sellOk(List.of(Map.of("mode", "UPI", "amountPaise", 5000, "moneyAccountId", qr.get("id")), Map.of("mode", "CASH", "amountPaise", 7500)));
    sellOk(List.of(Map.of("mode", "UPI", "amountPaise", 3000, "moneyAccountId", qr.get("id")), Map.of("mode", "CASH", "amountPaise", 9500)));
    sellOk(List.of(Map.of("mode", "UPI", "amountPaise", 3000, "moneyAccountId", qr.get("id")), Map.of("mode", "CASH", "amountPaise", 9500)));
    String a = "STK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String b = "STK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String c = "STK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String d = "STK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    // the machine payment is matched by its order id; Rs 125.50 is another amount than the bill, so it is a mismatch
    String csv = HEADER + row("T-" + a, id.replace("-", ""), "125.50", "1.00", "0.18", "124.32", "UTRP2", "Credit Card")
        + row("T-" + b, b, "50.00", "0.30", "0.05", "49.65", "UTRP2", "UPI")
        + row("T-" + c, c, "30.00", "0.20", "0.04", "29.76", "UTRP2", "UPI")
        + row("T-" + d, d, "77.77", "0.20", "0.04", "77.53", "UTRP2", "UPI");
    var imported = upload(csv, acc, Map.of("format", "paytm"));
    assertThat(imported.getStatusCode()).as(String.valueOf(imported.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(imported.getBody()).containsEntry("rows", 4).containsEntry("matched", 1).containsEntry("unmatched", 3);
    String sid = (String) imported.getBody().get("settlementId");
    List<Map> items = (List<Map>) get("/api/v1/admin/settlements/" + sid + "/items?size=50", admin).getBody().get("items");
    assertThat(items).filteredOn(i -> Boolean.TRUE.equals(i.get("matched"))).singleElement().satisfies(i -> assertThat(i).containsEntry("providerEntityId", "T-" + b));
    List<Map> mismatches = (List<Map>) get("/api/v1/admin/settlements/exceptions?status=OPEN&kind=AMOUNT_MISMATCH&size=100", admin).getBody().get("items");
    assertThat(mismatches).anySatisfy(e -> assertThat(e).containsEntry("expectedPaise", 12500).containsEntry("actualPaise", 12550));
    List<Map> unmatched = (List<Map>) get("/api/v1/admin/settlements/exceptions?status=OPEN&kind=UNMATCHED_ITEM&size=100", admin).getBody().get("items");
    assertThat(unmatched).extracting(e -> e.get("reference")).contains(c, d);
  }

  @Test
  void aBrokenOrUnknownPaytmFileIsRefusedClearly() {
    Map m = machine("Broken");
    String acc = (String) m.get("moneyAccountId");
    var noHeader = upload("a,b\n1,2\n", acc, Map.of("format", "PAYTM"));
    assertThat(noHeader.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(noHeader.getBody()).containsEntry("code", "CSV_INVALID");
    var badRow = upload(HEADER + row("T-bad", "O-bad", "abc", "0", "0", "0", "U", "UPI"), acc, Map.of());
    assertThat(badRow.getBody()).containsEntry("code", "CSV_INVALID");
    assertThat(badRow.getBody().get("details").toString()).contains("row 2");
    var noRows = upload(HEADER, acc, Map.of());
    assertThat(noRows.getBody()).containsEntry("code", "CSV_INVALID");
    var onlyFailed = upload(HEADER + "T9,O9," + today() + " 10:00:00,,ACQUIRING,FAILURE,10.00,0,0,0,,,N,UPI\n", acc, Map.of());
    assertThat(onlyFailed.getBody()).containsEntry("code", "CSV_INVALID");
    var unknownFormat = upload(HEADER, acc, Map.of("format", "PHONEPE"));
    assertThat(unknownFormat.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat((Map) ((Map) unknownFormat.getBody().get("details")).get("fields")).containsKey("format");
    // GENERIC forces the column mapping even for a Paytm header
    var generic = upload(HEADER + row("T-g", "O-g", "10.00", "0", "0", "10", "U", "UPI"), acc, Map.of("format", "generic", "dateColumn", "transaction date", "referenceColumn", "order id",
        "amountColumn", "amount", "dateFormat", "yyyy-MM-dd HH:mm:ss"));
    assertThat(generic.getStatusCode().value()).isIn(200, 400);
  }

  // ---------- the manual channel is unchanged ----------
  @Test
  void theManualChannelJourneyStillWorksWithTheSessionIdOnTheBill() {
    Map manual = account("Bank swipe machine", "CARD_TERMINAL", "manual", "", null);
    String acc = (String) manual.get("id");
    String ref = "MAN-" + UUID.randomUUID().toString().substring(0, 8);
    Map started = startOk(acc, 12500, ref);
    String id = (String) started.get("id");
    assertThat(started).containsEntry("provider", "manual").containsEntry("state", "PENDING").containsEntry("mode", "MANUAL").containsEntry("providerStatus", "PENDING")
        .containsEntry("moneyAccountId", acc);
    var early = sell(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    assertThat(early.getBody()).containsEntry("code", "TERMINAL_PAYMENT_NOT_PAID");
    var confirmed = post(TERMINAL_PAYMENTS + "/" + id + "/confirm", cashier, Map.of("approvalCode", "APPR77", "rrn", "RRN7788"));
    assertThat(confirmed.getBody()).containsEntry("state", "APPROVED").containsEntry("approvalCode", "APPR77").containsEntry("rrn", "RRN7788");
    assertThat(sell(List.of(Map.of("mode", "CARD", "amountPaise", 2500, "terminalPaymentId", id), Map.of("mode", "CASH", "amountPaise", 10000))).getBody())
        .containsEntry("code", "TERMINAL_PAYMENT_AMOUNT");
    Map sale = sellOk(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    Map line = jdbc.queryForMap("select reference, money_account_id::text acc, terminal_payment_id::text tp, detail::text detail from pos_payment where sale_id = ?::uuid", sale.get("id"));
    assertThat(line).containsEntry("reference", "RRN7788").containsEntry("acc", acc).containsEntry("tp", id);
    assertThat((String) line.get("detail")).contains("APPR77").contains("RRN7788");
    assertThat(jdbc.queryForObject("select pos_sale_id::text from card_terminal_session where id = ?::uuid", String.class, id)).isEqualTo((String) sale.get("id"));
    assertThat(sell(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id))).getBody()).containsEntry("code", "TERMINAL_PAYMENT_USED");
    assertThat(receipts("provider=manual&account=" + acc)).singleElement().satisfies(l -> assertThat(l).containsEntry("method", "CARD").containsEntry("reference", "RRN7788"));
    assertThat(post(TERMINAL_PAYMENTS + "/" + id + "/void", cashier, null).getBody()).containsEntry("code", "NOT_VOIDABLE");
    assertThat(post(TERMINAL_PAYMENTS + "/" + UUID.randomUUID() + "/void", cashier, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    // a manual session can be cancelled, a session id that nobody knows cannot be put on a bill
    String other = (String) startOk(acc, 900, "MAN-" + UUID.randomUUID()).get("id");
    assertThat(post(TERMINAL_PAYMENTS + "/" + other + "/cancel", cashier, null).getBody()).containsEntry("state", "CANCELLED");
    assertThat(sell(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", UUID.randomUUID()))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void theChosenChannelDecidesNotTheGlobalSettingWhichIsOnlyTheDefault() {
    Map m = machine("Dispatch");
    Map manual = account("Dispatch manual", "CARD_TERMINAL", "manual", "", null);
    Map unknown = account("Dispatch other", "GATEWAY", "pinelabs", "", null);
    call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(change("pos.terminalProvider", "paytm"))), null);
    // the default adapter is Paytm: a manual channel still uses the manual terminal, a channel of unknown provider falls back to Paytm's single machine rules
    assertThat(startOk(manual.get("id"), 1100, "D-" + UUID.randomUUID())).containsEntry("provider", "manual");
    assertThat(start(unknown.get("id"), 1200, "D-" + UUID.randomUUID()).getStatusCode()).as("several active machines: the till must choose one").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(startOk(m.get("moneyAccountId"), 1300, "D-" + UUID.randomUUID())).containsEntry("provider", "paytm");
    call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(change("pos.terminalProvider", "manual"))), null);
    // Paytm switched off: its machine channel is not available, the manual one is
    call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(change("paytm.enabled", "false"))), null);
    var off = start(m.get("moneyAccountId"), 1400, "D-" + UUID.randomUUID());
    assertThat(off.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(off.getBody()).containsEntry("code", "TERMINAL_NOT_CONFIGURED");
    assertThat(startOk(manual.get("id"), 1500, "D-" + UUID.randomUUID())).containsEntry("provider", "manual");
  }

  // ---------- states and restart ----------
  @Test
  void aSessionResumesAfterARestartBecauseTheBackgroundJobFinishesIt() {
    Map m = machine("Restart");
    String id = (String) startOk(m.get("moneyAccountId"), 12500, "RS-" + UUID.randomUUID()).get("id");
    // nothing polls from the till: a freshly started server runs the job, which picks the payment up from the database alone
    for (int i = 0; i < 3; i++) {
      jdbc.update("update terminal_payment set next_poll_at = now() - interval '1 second' where id = ?::uuid and next_poll_at is not null", id);
      paytmPayments.pollDue();
    }
    assertThat(jdbc.queryForObject("select status from terminal_payment where id = ?::uuid", String.class, id)).isEqualTo("SUCCESS");
    assertThat(get(TERMINAL_PAYMENTS + "/" + id, cashier).getBody()).containsEntry("state", "APPROVED").containsEntry("rrn", "412345678901");
    assertThat(sellOk(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)))).containsKey("invoiceNo");
  }

  @Test
  void everyStateOfTheMachineIsVisibleInTheGenericSession() {
    Map m = machine("States");
    String acc = (String) m.get("moneyAccountId");
    // cancel is a request to the cashier to press cancel on the machine; the machine reports it
    FAKE.next(Script.CANCELLED_ON_MACHINE);
    String id = (String) startOk(acc, 600, "ST-" + UUID.randomUUID()).get("id");
    var ask = post(TERMINAL_PAYMENTS + "/" + id + "/cancel", cashier, null);
    assertThat(ask.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ask.getBody()).containsEntry("code", "CANCEL_ON_MACHINE");
    assertThat(get(TERMINAL_PAYMENTS + "/" + id, cashier).getBody()).containsEntry("state", "PENDING").containsEntry("cancelOnMachine", true).containsEntry("open", true);
    Map gone = pollUntil(id, "CANCELLED");
    assertThat(gone).containsEntry("providerStatus", "CANCELLED").containsEntry("open", false);
    assertThat(post(TERMINAL_PAYMENTS + "/" + id + "/cancel", cashier, null).getBody()).containsEntry("state", "CANCELLED");

    // nobody pays: EXPIRED, and a late payment is still picked up
    FAKE.next(Script.PENDING_FOREVER);
    String expired = (String) startOk(acc, 600, "ST-" + UUID.randomUUID()).get("id");
    jdbc.update("update terminal_payment set created_at = created_at - interval '60 minutes' where id = ?::uuid", expired);
    assertThat(poll(expired)).containsEntry("state", "EXPIRED").containsEntry("providerStatus", "EXPIRED").containsEntry("open", false);
    FAKE.next(Script.CARD_SUCCESS);
    String late = (String) startOk(acc, 600, "ST-" + UUID.randomUUID()).get("id");
    jdbc.update("update terminal_payment set created_at = created_at - interval '60 minutes' where id = ?::uuid", late);
    assertThat(poll(late)).containsEntry("state", "EXPIRED");
    assertThat(poll(late)).containsEntry("state", "APPROVED").containsEntry("providerStatus", "SUCCESS");

    // a refusal, a failure and a same-day void
    FAKE.next(Script.BUSY);
    var rejected = start(acc, 600, "ST-" + UUID.randomUUID());
    assertThat(rejected.getStatusCode().value()).isEqualTo(422);
    assertThat(rejected.getBody()).containsEntry("code", "TERMINAL_REJECTED");
    FAKE.fallback(Script.UPI_SUCCESS);
    String upi = (String) startOk(acc, 700, "ST-" + UUID.randomUUID()).get("id");
    Map paid = pollUntil(upi, "APPROVED");
    assertThat(paid).containsEntry("payMethod", "UPI").containsEntry("canVoid", true);
    assertThat((String) paid.get("upiVpa")).contains("***").doesNotContain("customer@");
    Map voided = post(TERMINAL_PAYMENTS + "/" + upi + "/void", cashier, null).getBody();
    assertThat(voided).containsEntry("providerStatus", "VOIDED").containsEntry("state", "CANCELLED");
    FAKE.fallback(Script.CARD_SUCCESS);
  }

  @Test
  void aPaytmUpiPaymentGoesOnAUpiLineAndShowsAsUpiInTheReceipts() {
    Map m = machine("Upi on machine");
    String acc = (String) m.get("moneyAccountId");
    FAKE.fallback(Script.UPI_SUCCESS);
    String id = (String) startOk(acc, 12500, "UPI-" + UUID.randomUUID()).get("id");
    pollUntil(id, "APPROVED");
    var wrongMode = sell(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    assertThat(wrongMode.getBody()).containsEntry("code", "TERMINAL_PAYMENT_MODE");
    Map sale = sellOk(List.of(Map.of("mode", "UPI", "amountPaise", 12500, "terminalPaymentId", id)));
    assertThat(receipts("provider=paytm&method=UPI&account=" + acc)).singleElement().satisfies(l -> assertThat(l).containsEntry("payer", "cu***@okbank").containsEntry("reference", "998877665544")
        .containsEntry("documentNo", sale.get("invoiceNo")));
    // the machine's UPI lines count as money not yet settled until a settlement claims them
    List<Map> unsettled = (List<Map>) get("/api/v1/admin/settlements/unsettled?size=100", admin).getBody().get("items");
    assertThat(unsettled).anySatisfy(u -> assertThat((Map) u.get("row")).containsEntry("source", "COUNTER").containsEntry("reference", sale.get("invoiceNo")));
    FAKE.fallback(Script.CARD_SUCCESS);
  }

  // ---------- several vendors, isolated syncs ----------
  @Test
  void oneProviderThatFailsDoesNotStopAnotherWhenTheNightlyJobRuns() {
    String setId = "fake2-" + UUID.randomUUID().toString().substring(0, 8);
    Instant now = Instant.now();
    Summary s = new Summary(setId, "UTR-" + setId, 1000, 10, 2, 988, "processed", now);
    fake2.settlements.add(s);
    fake2.details.put(setId, new Detail(s, List.of(new Item("e1", ItemKind.PAYMENT, "pay_unknown_" + setId, null, 1000, 10, 2, 988, "upi", now, "UTR-" + setId, null))));
    fake.failListing = true;
    settlements.nightly();
    assertThat(jdbc.queryForObject("select last_error from settlement_sync_state where provider = 'fake'", String.class)).contains("provider down");
    assertThat(jdbc.queryForObject("select count(*) from settlement where provider = 'fake2' and provider_settlement_id = ?", Integer.class, setId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select last_error from settlement_sync_state where provider = 'fake2'", String.class)).isNull();
    assertThat(jdbc.queryForObject("select last_synced_at is not null from settlement_sync_state where provider = 'fake2'", Boolean.class)).isTrue();
    fake.failListing = false;
  }
}
