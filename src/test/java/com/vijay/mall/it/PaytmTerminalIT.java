package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.paytm.PaytmFakeServer;
import com.vijay.mall.payment.paytm.PaytmFakeServer.Script;
import com.vijay.mall.payment.paytm.app.TerminalPaymentService;
import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The Paytm card machine end to end: admin set-up, till flow, polling, expiry, void, and use on a counter bill, against the in-JVM Paytm fake. */
@SuppressWarnings({"unchecked", "rawtypes"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaytmTerminalIT extends IntegrationTestBase {
  private static final PaytmFakeServer FAKE = new PaytmFakeServer();
  private static final String CASHIER = "cashier@vijaysupermall.local";
  private static final String ADMIN_TERMINALS = "/api/v1/admin/payment-terminals";
  private static final String PAYMENTS = "/api/v1/pos/terminal-payments";

  @Autowired private TerminalPaymentService service;

  private static Map change(String key, String value) { return Map.of("key", key, "value", value); }

  private void configure(boolean enabled) {
    var res = call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(
        change("paytm.enabled", Boolean.toString(enabled)), change("paytm.baseUrl", FAKE.baseUrl()), change("paytm.mid", "TESTMID123"),
        change("paytm.merchantKey", PaytmFakeServer.KEY), change("paytm.channelId", "EDC"), change("paytm.pollSeconds", "60"))), null);
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
  }

  @BeforeEach
  void setUp() {
    configure(true);
    FAKE.voidCode = "0009";
    FAKE.fallback(Script.CARD_SUCCESS);
    FAKE.inactiveTerminals.clear();
    restock("Basmati Rice", 100);
  }

  @DynamicPropertySource
  static void paytmProps(DynamicPropertyRegistry r) { r.add("app.paytm.starts-per-minute", () -> 100); }

  @AfterAll
  void clean() {
    FAKE.close();
    call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.enabled", "false"), change("paytm.mid", ""),
        change("paytm.merchantKey", ""), change("paytm.channelId", ""), change("paytm.baseUrl", ""), change("paytm.pollSeconds", ""))), null);
    jdbc.update("update payment_terminal set active = false");
  }

  private String cashier() { return token(CASHIER); }

  private static String tid() { return UUID.randomUUID().toString().replace("-", "").substring(0, 8); }

  private Map newTerminal(String name) {
    var res = post(ADMIN_TERMINALS, adminToken(), Map.of("terminalId", tid(), "name", name));
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private org.springframework.http.ResponseEntity<Map> start(String token, Object terminalId, long amount, String key) {
    return call(HttpMethod.POST, PAYMENTS, token, Map.of("terminalId", terminalId, "amountPaise", amount, "reference", "INV/1"), key);
  }

  private Map startOk(Object terminalId, long amount) {
    var res = start(cashier(), terminalId, amount, UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private void due(Object id) {
    jdbc.update("update terminal_payment set next_poll_at = now() - interval '1 second' where id = ?::uuid and next_poll_at is not null", id);
  }

  private Map poll(Object id) {
    due(id);
    var res = get(PAYMENTS + "/" + id, cashier());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    return res.getBody();
  }

  private Map pollUntil(Object id, String status) {
    Map m = null;
    for (int i = 0; i < 6; i++) {
      m = poll(id);
      if (status.equals(m.get("status"))) return m;
    }
    throw new AssertionError("never reached " + status + ": " + m);
  }

  private void ageMinutes(Object id, int minutes) {
    jdbc.update("update terminal_payment set created_at = created_at - make_interval(mins => ?) where id = ?::uuid", minutes, id);
  }

  // ---------- access and set-up ----------
  @Test
  void accessRules() {
    String customer = token(freshEmail());
    assertThat(get(ADMIN_TERMINALS, cashier()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get(ADMIN_TERMINALS, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/v1/pos/terminals", customer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/pos/terminals", cashier()).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(post(PAYMENTS, customer, Map.of("terminalId", UUID.randomUUID(), "amountPaise", 100)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/config", cashier(), Map.of("changes", List.of(change("paytm.pollSeconds", "5"))), null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void adminManagesTerminalsAndTheTillSeesTheActiveOnes() {
    jdbc.update("update payment_terminal set active = false");
    Map t = newTerminal("Counter 1");
    String id = (String) t.get("id");
    assertThat(t).containsEntry("provider", "paytm").containsEntry("active", true);
    var bad = post(ADMIN_TERMINALS, adminToken(), Map.of("terminalId", "short", "name", "X"));
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat((Map) ((Map) bad.getBody().get("details")).get("fields")).containsKey("terminalId");
    assertThat(post(ADMIN_TERMINALS, adminToken(), Map.of("terminalId", t.get("terminalId"), "name", "Dup")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(post(ADMIN_TERMINALS, adminToken(), Map.of("terminalId", tid(), "name", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    List<Map> till = (List<Map>) get("/api/v1/pos/terminals", cashier()).getBody().get("_array");
    assertThat(till).hasSize(1);
    assertThat(till.get(0)).containsEntry("name", "Counter 1").containsEntry("default", true).containsEntry("provider", "paytm").containsKey("terminalId");

    Map second = newTerminal("Counter 2");
    till = (List<Map>) get("/api/v1/pos/terminals", cashier()).getBody().get("_array");
    assertThat(till).extracting(m -> m.get("name")).containsExactly("Counter 1", "Counter 2");
    assertThat(till).allMatch(m -> Boolean.FALSE.equals(m.get("default")));

    UUID account = UUID.randomUUID();
    var upd = call(HttpMethod.PUT, ADMIN_TERMINALS + "/" + id, adminToken(), Map.of("name", "Front desk", "active", true, "moneyAccountId", account), null);
    assertThat(upd.getBody()).containsEntry("name", "Front desk").containsEntry("moneyAccountId", account.toString());
    assertThat(call(HttpMethod.PUT, ADMIN_TERMINALS + "/" + UUID.randomUUID(), adminToken(), Map.of("name", "x"), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    var page = get(ADMIN_TERMINALS + "?page=0&size=1", adminToken());
    assertThat((List) page.getBody().get("items")).hasSize(1);
    assertThat(page.getBody().get("hasMore")).isEqualTo(true);

    assertThat(post(ADMIN_TERMINALS + "/" + second.get("id") + "/deactivate", adminToken(), null).getBody()).containsEntry("active", false);
    till = (List<Map>) get("/api/v1/pos/terminals", cashier()).getBody().get("_array");
    assertThat(till).hasSize(1);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity = 'payment_terminal' and entity_id = ?", Integer.class, id)).isGreaterThanOrEqualTo(2);
  }

  @Test
  void testButtonReportsEachSituation() {
    Map t = newTerminal("Test me");
    String url = ADMIN_TERMINALS + "/" + t.get("id") + "/test";
    var ok = post(url, adminToken(), null);
    assertThat(ok.getBody()).containsEntry("ok", true).containsEntry("code", "OK");
    FAKE.inactiveTerminals.add((String) t.get("terminalId"));
    assertThat(post(url, adminToken(), null).getBody()).containsEntry("ok", false).containsEntry("code", "TERMINAL_INACTIVE");
    FAKE.inactiveTerminals.clear();
    call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.merchantKey", "fedcba9876543210"))), null);
    assertThat(post(url, adminToken(), null).getBody()).containsEntry("code", "BAD_CREDENTIALS");
    assertThat(post(ADMIN_TERMINALS + "/" + UUID.randomUUID() + "/test", adminToken(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post(url, cashier(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void configurationIsValidated() {
    var bad = call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.baseUrl", "http://evil.example"))), null);
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.merchantKey", "tooshort"))), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.mid", "bad mid!"))), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    var clear = call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.mid", ""))), null);
    assertThat(clear.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(String.valueOf(clear.getBody())).contains("merchant id").doesNotContain(PaytmFakeServer.KEY);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.enabled", "false"), change("paytm.mid", ""))), null)
        .getStatusCode()).isEqualTo(HttpStatus.OK);
    var view = get("/api/v1/admin/config", adminToken()).getBody();
    List<Map> groups = (List<Map>) view.get("groups");
    Map paytm = groups.stream().filter(g -> "PAYTM".equals(g.get("group"))).findFirst().orElseThrow();
    List<Map> items = (List<Map>) paytm.get("items");
    assertThat(items).extracting(i -> i.get("key")).contains("paytm.enabled", "paytm.environment", "paytm.baseUrl", "paytm.mid", "paytm.merchantKey",
        "paytm.channelId", "paytm.pollSeconds", "paytm.waitSeconds", "paytm.autoAccept", "paytm.paymentMode");
    Map key = items.stream().filter(i -> "paytm.merchantKey".equals(i.get("key"))).findFirst().orElseThrow();
    assertThat(key).containsEntry("secret", true).containsEntry("set", true);
    assertThat(key.get("value")).isNull();
    assertThat(jdbc.queryForObject("select secret from app_setting where name = 'paytm.merchantKey'", Boolean.class)).isTrue();
    assertThat(jdbc.queryForObject("select value from app_setting where name = 'paytm.merchantKey'", String.class)).isNotEqualTo(PaytmFakeServer.KEY);
    configure(true);
  }

  @Test
  void notConfiguredRefusesToStart() {
    Map t = newTerminal("Off");
    call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.enabled", "false"))), null);
    var res = start(cashier(), t.get("id"), 100, UUID.randomUUID().toString());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(res.getBody()).containsEntry("code", "PAYTM_NOT_CONFIGURED");
    service.pollDue();
  }

  @Test
  void startValidatesItsInput() {
    Map t = newTerminal("Validate");
    String c = cashier();
    assertThat(start(c, t.get("id"), 100, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(start(c, t.get("id"), 0, "k1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(start(c, UUID.randomUUID(), 100, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, PAYMENTS, c, Map.of("amountPaise", 100), "k2").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    post(ADMIN_TERMINALS + "/" + t.get("id") + "/deactivate", adminToken(), null);
    var off = start(c, t.get("id"), 100, UUID.randomUUID().toString());
    assertThat(off.getStatusCode().value()).isEqualTo(422);
    assertThat(off.getBody()).containsEntry("code", "TERMINAL_INACTIVE");
    assertThat(get(PAYMENTS + "/" + UUID.randomUUID(), c).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThatThrownBy(() -> service.start(UUID.randomUUID(), "k".repeat(101), UUID.randomUUID(), 100, "")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.start(UUID.randomUUID(), "k9", UUID.randomUUID(), 100, "x".repeat(51))).isInstanceOf(ApiException.class);
  }

  @Test
  void startsAreRateLimitedPerUser() {
    UUID who = UUID.randomUUID();
    for (int i = 0; i < 100; i++) {
      assertThatThrownBy(() -> service.start(who, UUID.randomUUID().toString(), UUID.randomUUID(), 100, "")).extracting("code").isEqualTo("VALIDATION_FAILED");
    }
    assertThatThrownBy(() -> service.start(who, UUID.randomUUID().toString(), UUID.randomUUID(), 100, "")).extracting("code").isEqualTo("RATE_LIMITED");
  }

  // ---------- the till flow ----------
  @Test
  void cardPaymentRunsFromRequestToSuccess() {
    Map t = newTerminal("Card");
    String key = UUID.randomUUID().toString();
    var first = start(cashier(), t.get("id"), 12500, key);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map p = first.getBody();
    assertThat(p).containsEntry("status", "PENDING").containsEntry("open", true).containsEntry("canCancel", true).containsEntry("amountPaise", 12500)
        .containsEntry("pollAfterSeconds", 60);
    String id = (String) p.get("id");
    assertThat(start(cashier(), t.get("id"), 12500, key).getBody()).containsEntry("id", id);
    assertThat(FAKE.count("/ecr/payment/request")).isGreaterThan(0);
    assertThat(FAKE.lastBody.path("merchantTransactionId").asString()).isEqualTo(id.replace("-", ""));

    var busy = start(cashier(), t.get("id"), 500, UUID.randomUUID().toString());
    assertThat(busy.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(busy.getBody()).containsEntry("code", "TERMINAL_BUSY");
    assertThat((Map) busy.getBody().get("details")).containsEntry("openPaymentId", id);

    long statusCalls = FAKE.count("/ecr/V2/payment/status");
    assertThat(get(PAYMENTS + "/" + id, cashier()).getBody()).containsEntry("status", "PENDING");
    assertThat(FAKE.count("/ecr/V2/payment/status")).isEqualTo(statusCalls);

    var cancel = post(PAYMENTS + "/" + id + "/cancel", cashier(), null);
    assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(cancel.getBody()).containsEntry("code", "CANCEL_ON_MACHINE");

    Map done = pollUntil(id, "SUCCESS");
    assertThat(done).containsEntry("open", false).containsEntry("payMethod", "CREDIT_CARD").containsEntry("cardScheme", "VISA")
        .containsEntry("maskedCard", "411111XXXXXX1111").containsEntry("rrn", "412345678901").containsEntry("authCode", "A1B2C3")
        .containsEntry("issuingBank", "HDFC Bank").containsEntry("canVoid", true);
    assertThat(service.confirmedPayment(UUID.fromString(id)).rrn()).isEqualTo("412345678901");
    assertThat(post(PAYMENTS + "/" + id + "/cancel", cashier(), null).getBody()).containsEntry("code", "ALREADY_PAID");
    assertThat(get(PAYMENTS + "/" + id, cashier()).getBody()).containsEntry("status", "SUCCESS");
    assertThat(startOk(t.get("id"), 100)).containsEntry("status", "PENDING");
  }

  @Test
  void theBackgroundJobResolvesOpenPayments() {
    Map t = newTerminal("Job");
    Map p = startOk(t.get("id"), 700);
    String id = (String) p.get("id");
    for (int i = 0; i < 4 && !"SUCCESS".equals(jdbc.queryForObject("select status from terminal_payment where id = ?::uuid", String.class, id)); i++) {
      due(id);
      service.pollDue();
    }
    assertThat(jdbc.queryForObject("select status from terminal_payment where id = ?::uuid", String.class, id)).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("select next_poll_at from terminal_payment where id = ?::uuid", java.sql.Timestamp.class, id)).isNull();
  }

  @Test
  void upiPaymentThenSameDayVoid() {
    Map t = newTerminal("Upi");
    FAKE.next(Script.UPI_SUCCESS);
    String id = (String) startOk(t.get("id"), 5000).get("id");
    Map done = pollUntil(id, "SUCCESS");
    assertThat(done).containsEntry("payMethod", "UPI").containsEntry("upiVpa", "cu***@okbank");
    FAKE.voidCode = "0182";
    var refused = post(PAYMENTS + "/" + id + "/void", cashier(), null);
    assertThat(refused.getStatusCode().value()).isEqualTo(422);
    assertThat(refused.getBody()).containsEntry("code", "VOID_REJECTED");
    assertThat(get(PAYMENTS + "/" + id, cashier()).getBody()).containsEntry("status", "SUCCESS");
    FAKE.voidCode = "0009";
    var voided = post(PAYMENTS + "/" + id + "/void", cashier(), null);
    assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(voided.getBody()).containsEntry("status", "VOIDED").containsEntry("canVoid", false);
    assertThat(post(PAYMENTS + "/" + id + "/void", cashier(), null).getBody()).containsEntry("status", "VOIDED");
    assertThat(post(PAYMENTS + "/" + id + "/cancel", cashier(), null).getBody()).containsEntry("status", "VOIDED");
    assertThat(post(PAYMENTS + "/" + id + "/void", cashier(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void voidRulesOnTheDayAndOnState() {
    Map t = newTerminal("Rules");
    FAKE.next(Script.PENDING_FOREVER);
    String open = (String) startOk(t.get("id"), 300).get("id");
    var notPaid = post(PAYMENTS + "/" + open + "/void", cashier(), null);
    assertThat(notPaid.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(notPaid.getBody()).containsEntry("code", "NOT_VOIDABLE");
    assertThat(post(PAYMENTS + "/" + UUID.randomUUID() + "/void", cashier(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    Map t2 = newTerminal("Rules2");
    String paid = (String) startOk(t2.get("id"), 300).get("id");
    pollUntil(paid, "SUCCESS");
    jdbc.update("update terminal_payment set finished_at = finished_at - interval '2 days' where id = ?::uuid", paid);
    var late = post(PAYMENTS + "/" + paid + "/void", cashier(), null);
    assertThat(late.getStatusCode().value()).isEqualTo(422);
    assertThat(late.getBody()).containsEntry("code", "VOID_WINDOW_PASSED");
    assertThat((String) late.getBody().get("message")).contains("dashboard");
    assertThat(get(PAYMENTS + "/" + paid, cashier()).getBody()).containsEntry("canVoid", false);

    call(HttpMethod.PUT, "/api/v1/admin/config", adminToken(), Map.of("changes", List.of(change("paytm.baseUrl", "http://127.0.0.1:1"))), null);
    jdbc.update("update terminal_payment set status = 'SUCCESS', finished_at = now() where id = ?::uuid", open);
    var down = post(PAYMENTS + "/" + open + "/void", cashier(), null);
    assertThat(down.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(down.getBody()).containsEntry("code", "VOID_UNKNOWN");
  }

  @Test
  void rejectionsAreExplainedAndFreeTheMachine() {
    Map t = newTerminal("Reject");
    FAKE.next(Script.TERMINAL_INACTIVE).next(Script.BAD_CHECKSUM).next(Script.BUSY);
    for (String reason : List.of("TERMINAL_INACTIVE", "BAD_CHECKSUM", "TERMINAL_BUSY_AT_PAYTM")) {
      var res = start(cashier(), t.get("id"), 900, UUID.randomUUID().toString());
      assertThat(res.getStatusCode().value()).isEqualTo(422);
      assertThat(res.getBody()).containsEntry("code", "TERMINAL_REJECTED");
      assertThat((Map) res.getBody().get("details")).containsEntry("reason", reason).containsKey("fields");
    }
    assertThat(jdbc.queryForObject("select count(*) from terminal_payment where payment_terminal_id = ?::uuid and status = 'FAILED'", Integer.class, t.get("id"))).isEqualTo(3);
    assertThat(startOk(t.get("id"), 900)).containsEntry("status", "PENDING");
  }

  @Test
  void aLostAnswerIsResolvedByAStatusCheck() {
    Map t = newTerminal("Lost");
    FAKE.next(Script.DROP_THEN_CARD);
    Map p = startOk(t.get("id"), 1100);
    assertThat(p).containsEntry("status", "UNKNOWN").containsEntry("open", true);
    assertThat(FAKE.count("/ecr/payment/request")).isGreaterThan(0);
    long sales = FAKE.count("/ecr/payment/request");
    assertThat(start(cashier(), t.get("id"), 1100, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(FAKE.count("/ecr/payment/request")).isEqualTo(sales);
    assertThat(pollUntil((String) p.get("id"), "SUCCESS")).containsEntry("rrn", "412345678901");
  }

  @Test
  void aSaleThatNeverArrivedBecomesFailedAndAnOddCodeStaysUnknownUntilResolved() {
    Map t = newTerminal("Never");
    FAKE.next(Script.SERVER_ERROR_NEVER_ARRIVED);
    Map p = startOk(t.get("id"), 400);
    assertThat(p).containsEntry("status", "UNKNOWN");
    String id = (String) p.get("id");
    assertThat(poll(id)).containsEntry("status", "UNKNOWN");
    ageMinutes(id, 3);
    Map failed = poll(id);
    assertThat(failed).containsEntry("status", "FAILED").containsEntry("open", false);
    assertThat((String) failed.get("message")).contains("Nothing was charged");

    FAKE.next(Script.UNKNOWN_CODE).next(Script.DUPLICATE);
    for (int i = 0; i < 2; i++) {
      Map odd = startOk(t.get("id"), 400);
      assertThat(odd).containsEntry("status", "UNKNOWN");
      ageMinutes((String) odd.get("id"), 3);
      assertThat(poll((String) odd.get("id"))).containsEntry("status", "FAILED");
    }
  }

  @Test
  void cancelledOnTheMachineWhenTheCashierAskedBecomesCancelledOtherwiseFailed() {
    Map t = newTerminal("Cancel");
    FAKE.next(Script.CANCELLED_ON_MACHINE);
    String id = (String) startOk(t.get("id"), 800).get("id");
    var wait = post(PAYMENTS + "/" + id + "/cancel", cashier(), null);
    assertThat(wait.getBody()).containsEntry("code", "CANCEL_ON_MACHINE");
    assertThat(pollUntil(id, "CANCELLED")).containsEntry("open", false);
    assertThat(post(PAYMENTS + "/" + id + "/cancel", cashier(), null).getBody()).containsEntry("status", "CANCELLED");

    FAKE.next(Script.CANCELLED_ON_MACHINE);
    String id2 = (String) startOk(t.get("id"), 800).get("id");
    assertThat(pollUntil(id2, "FAILED")).containsEntry("open", false);
    FAKE.next(Script.CANCELLED_ON_MACHINE);
    String id3 = (String) startOk(t.get("id"), 800).get("id");
    ageMinutes(id3, 1);
    jdbc.update("update terminal_payment set cancel_requested_at = now() where id = ?::uuid", id3);
    assertThat(poll(id3)).containsEntry("status", "PENDING").containsEntry("open", true);
  }

  @Test
  void anUnpaidPaymentExpiresAndALatePaymentIsStillPickedUp() {
    Map t = newTerminal("Expire");
    FAKE.next(Script.PENDING_FOREVER);
    String id = (String) startOk(t.get("id"), 600).get("id");
    ageMinutes(id, 60);
    Map expired = poll(id);
    assertThat(expired).containsEntry("status", "EXPIRED").containsEntry("open", false);
    assertThat(jdbc.queryForObject("select next_poll_at is not null from terminal_payment where id = ?::uuid", Boolean.class, id)).isTrue();
    assertThat(poll(id)).containsEntry("status", "EXPIRED");
    jdbc.update("update terminal_payment set finished_at = finished_at - interval '1 hour' where id = ?::uuid", id);
    assertThat(poll(id)).containsEntry("status", "EXPIRED");
    assertThat(jdbc.queryForObject("select next_poll_at is null from terminal_payment where id = ?::uuid", Boolean.class, id)).isTrue();

    String late = (String) startOk(t.get("id"), 600).get("id");
    ageMinutes(late, 60);
    assertThat(poll(late)).containsEntry("status", "EXPIRED");
    assertThat(poll(late)).containsEntry("status", "SUCCESS").containsEntry("rrn", "412345678901");

    FAKE.next(Script.CANCELLED_ON_MACHINE);
    String gone = (String) startOk(t.get("id"), 600).get("id");
    ageMinutes(gone, 60);
    assertThat(poll(gone)).containsEntry("status", "EXPIRED");
    assertThat(poll(gone)).containsEntry("status", "EXPIRED");
    assertThat(jdbc.queryForObject("select next_poll_at is null from terminal_payment where id = ?::uuid", Boolean.class, gone)).isTrue();
  }

  @Test
  void aDifferentAmountFromPaytmIsNotTrusted() {
    Map t = newTerminal("Wrong");
    FAKE.next(Script.WRONG_AMOUNT);
    String id = (String) startOk(t.get("id"), 1000).get("id");
    Map m = poll(id);
    assertThat(m).containsEntry("status", "UNKNOWN");
    assertThat((String) m.get("message")).contains("different amount");
    ageMinutes(id, 60);
    assertThat(poll(id)).containsEntry("status", "EXPIRED");
    assertThat(poll(id)).containsEntry("status", "EXPIRED");
  }

  // ---------- on a counter bill ----------
  private Object riceId() {
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", cashier()).getBody().get("items");
    return items.stream().filter(p -> "Basmati Rice".equals(p.get("nameEn")) && "1 kg".equals(p.get("packSize"))).findFirst().orElseThrow().get("id");
  }

  private org.springframework.http.ResponseEntity<Map> sellWith(List<Map> payments) {
    return call(HttpMethod.POST, "/api/v1/pos/sales", cashier(), Map.of("cart", Map.of("items", List.of(Map.of("productId", riceId(), "qty", 1))), "payments", payments),
        UUID.randomUUID().toString());
  }

  @Test
  void aPaidCardPaymentIsAttachedToTheBillOnce() {
    Map t = newTerminal("Bill");
    String id = (String) startOk(t.get("id"), 12500).get("id");
    var early = sellWith(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    assertThat(early.getStatusCode().value()).isEqualTo(422);
    assertThat(early.getBody()).containsEntry("code", "TERMINAL_PAYMENT_NOT_PAID");
    pollUntil(id, "SUCCESS");

    assertThat(sellWith(List.of(Map.of("mode", "CASH", "amountPaise", 12500, "terminalPaymentId", id))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var wrongMode = sellWith(List.of(Map.of("mode", "UPI", "amountPaise", 12500, "terminalPaymentId", id)));
    assertThat(wrongMode.getBody()).containsEntry("code", "TERMINAL_PAYMENT_MODE");
    var wrongAmount = sellWith(List.of(Map.of("mode", "CARD", "amountPaise", 2500, "terminalPaymentId", id), Map.of("mode", "CASH", "amountPaise", 10000)));
    assertThat(wrongAmount.getBody()).containsEntry("code", "TERMINAL_PAYMENT_AMOUNT");
    assertThat(sellWith(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", UUID.randomUUID()))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    var sale = sellWith(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    assertThat(sale.getStatusCode()).as(String.valueOf(sale.getBody())).isEqualTo(HttpStatus.CREATED);
    String saleId = (String) sale.getBody().get("id");
    assertThat(jdbc.queryForObject("select reference from pos_payment where sale_id = ?::uuid", String.class, saleId)).isEqualTo("PAYTM RRN 412345678901 AUTH A1B2C3");
    assertThat(jdbc.queryForObject("select terminal_payment_id::text from pos_payment where sale_id = ?::uuid", String.class, saleId)).isEqualTo(id);
    assertThat(get(PAYMENTS + "/" + id, cashier()).getBody()).containsEntry("posSaleId", saleId).containsEntry("canVoid", false);

    var again = sellWith(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(again.getBody()).containsEntry("code", "TERMINAL_PAYMENT_USED");
    var voidUsed = post(PAYMENTS + "/" + id + "/void", cashier(), null);
    assertThat(voidUsed.getBody()).containsEntry("code", "PAYMENT_IN_USE");
    assertThatThrownBy(() -> service.confirmedPayment(UUID.fromString(id))).extracting("code").isEqualTo("TERMINAL_PAYMENT_USED");
  }

  @Test
  void upiPaymentOnABillKeepsExplicitReferenceAndOldPayloadsStillWork() {
    Map t = newTerminal("BillUpi");
    FAKE.next(Script.UPI_SUCCESS);
    String id = (String) startOk(t.get("id"), 12500).get("id");
    pollUntil(id, "SUCCESS");
    var sale = sellWith(List.of(Map.of("mode", "UPI", "amountPaise", 12500, "reference", "MYREF", "terminalPaymentId", id)));
    assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(jdbc.queryForObject("select reference from pos_payment where sale_id = ?::uuid", String.class, sale.getBody().get("id"))).isEqualTo("MYREF");
    assertThat(sellWith(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "reference", "APPR"))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var mismatch = sellWith(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThatThrownBy(() -> service.confirmedPayment(UUID.randomUUID())).isInstanceOf(ApiException.class);
  }

  @Test
  void aVoidedPaymentCannotPayABill() {
    Map t = newTerminal("BillVoid");
    String id = (String) startOk(t.get("id"), 12500).get("id");
    pollUntil(id, "SUCCESS");
    assertThat(post(PAYMENTS + "/" + id + "/void", cashier(), null).getBody()).containsEntry("status", "VOIDED");
    var sale = sellWith(List.of(Map.of("mode", "CARD", "amountPaise", 12500, "terminalPaymentId", id)));
    assertThat(sale.getBody()).containsEntry("code", "TERMINAL_PAYMENT_NOT_PAID");
  }
}
