package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.support.IntegrationTestBase;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.infra.WalletRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The coin ledger against real PostgreSQL: idempotency, FIFO lots, reversal, expiry, locking, admin rules. */
class WalletLedgerIT extends IntegrationTestBase {
  @Autowired WalletService wallet;
  @Autowired WalletRepository walletRepo;
  @Autowired UserRepository users;

  private UUID newUser() { return users.insert(freshEmail(), User.CUSTOMER, Instant.now()).id(); }

  private int lotRemaining(UUID user, UUID order, String kind) {
    Integer r = jdbc.queryForObject("SELECT remaining FROM coin_ledger WHERE user_id = ? AND order_id = ? AND kind = ?",
        Integer.class, user, order, kind);
    return r == null ? 0 : r;
  }

  private void setLotExpiry(UUID user, UUID order, Instant when) {
    jdbc.update("UPDATE coin_ledger SET expires_at = ? WHERE user_id = ? AND order_id = ? AND kind = 'EARN'",
        Timestamp.from(when), user, order);
  }

  private int ledgerCount(UUID user, String kind) {
    return jdbc.queryForObject("SELECT count(*) FROM coin_ledger WHERE user_id = ? AND kind = ?", Integer.class, user, kind);
  }

  @Test
  void creditIsIdempotentPerOrder() {
    UUID u = newUser();
    UUID order = UUID.randomUUID();
    wallet.credit(u, 12, order);
    wallet.credit(u, 12, order);
    assertThat(wallet.balance(u)).isEqualTo(12);
    assertThat(ledgerCount(u, "EARN")).isEqualTo(1);
    wallet.credit(u, 0, UUID.randomUUID());
    assertThat(wallet.balance(u)).isEqualTo(12);
  }

  @Test
  void redeemSpendsTheLotThatExpiresFirstAndReverseGivesItBack() {
    UUID u = newUser();
    UUID late = UUID.randomUUID(), soon = UUID.randomUUID(), spend = UUID.randomUUID();
    wallet.credit(u, 10, late);
    wallet.credit(u, 10, soon);
    setLotExpiry(u, late, Instant.now().plusSeconds(90 * 86400L));
    setLotExpiry(u, soon, Instant.now().plusSeconds(10 * 86400L));

    wallet.redeem(u, 14, spend);
    assertThat(wallet.balance(u)).isEqualTo(6);
    assertThat(lotRemaining(u, soon, "EARN")).isZero();
    assertThat(lotRemaining(u, late, "EARN")).isEqualTo(6);

    wallet.reverse(u, spend);
    assertThat(wallet.balance(u)).isEqualTo(20);
    assertThat(lotRemaining(u, soon, "EARN")).isEqualTo(10);
    assertThat(lotRemaining(u, late, "EARN")).isEqualTo(10);

    wallet.reverse(u, spend); // second reverse changes nothing
    assertThat(wallet.balance(u)).isEqualTo(20);
  }

  @Test
  void redeemingTheSameOrderTwiceSpendsOnce() {
    UUID u = newUser();
    wallet.credit(u, 10, UUID.randomUUID());
    UUID order = UUID.randomUUID();
    wallet.redeem(u, 4, order);
    wallet.redeem(u, 4, order);
    assertThat(wallet.balance(u)).isEqualTo(6);
  }

  @Test
  void cannotRedeemMoreThanTheBalance() {
    UUID u = newUser();
    wallet.credit(u, 5, UUID.randomUUID());
    assertThatThrownBy(() -> wallet.redeem(u, 6, UUID.randomUUID())).isInstanceOf(ApiException.class);
    assertThat(wallet.balance(u)).isEqualTo(5);
    wallet.redeem(u, 0, UUID.randomUUID()); // nothing to do
    assertThat(wallet.balance(u)).isEqualTo(5);
  }

  @Test
  void reverseOfOrderThatNeverRedeemedIsANoOp() {
    UUID u = newUser();
    wallet.credit(u, 5, UUID.randomUUID());
    wallet.reverse(u, UUID.randomUUID());
    assertThat(wallet.balance(u)).isEqualTo(5);
  }

  @Test
  void coinsFromAnExpiredLotComeBackInAFreshLot() {
    UUID u = newUser();
    UUID earn = UUID.randomUUID(), spend = UUID.randomUUID();
    wallet.credit(u, 10, earn);
    wallet.redeem(u, 10, spend);
    setLotExpiry(u, earn, Instant.now().minusSeconds(3600));
    wallet.reverse(u, spend);
    assertThat(wallet.balance(u)).isEqualTo(10);
    assertThat(ledgerCount(u, "ADJUST")).isEqualTo(1);
    assertThat(lotRemaining(u, spend, "ADJUST")).isEqualTo(10);
  }

  @Test
  void expiryJobRemovesExpiredCoinsExactlyOnce() {
    UUID u = newUser();
    UUID earn = UUID.randomUUID();
    wallet.credit(u, 8, earn);
    wallet.credit(u, 5, UUID.randomUUID());
    setLotExpiry(u, earn, Instant.now().minusSeconds(60));
    wallet.expireLots();
    assertThat(wallet.balance(u)).isEqualTo(5);
    assertThat(ledgerCount(u, "EXPIRE")).isEqualTo(1);
    wallet.expireLots();
    assertThat(wallet.balance(u)).isEqualTo(5);
    assertThat(ledgerCount(u, "EXPIRE")).isEqualTo(1);
  }

  @Test
  void consistencyCheckSpotsABalanceThatDisagreesWithTheLedger() {
    UUID u = newUser();
    wallet.credit(u, 10, UUID.randomUUID());
    assertThat(walletRepo.mismatches()).noneMatch(m -> m.userId().equals(u));
    jdbc.update("UPDATE wallet_balance SET coins = 99 WHERE user_id = ?", u);
    assertThat(walletRepo.mismatches()).anyMatch(m -> m.userId().equals(u) && m.balance() == 99 && m.ledgerSum() == 10);
    wallet.consistencyCheck(); // logs an error, must not throw
    jdbc.update("UPDATE wallet_balance SET coins = 10 WHERE user_id = ?", u);
  }

  @Test
  void parallelRedemptionsNeverSpendMoreThanTheBalance() throws Exception {
    UUID u = newUser();
    wallet.credit(u, 10, UUID.randomUUID());
    AtomicInteger ok = new AtomicInteger();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> fs = IntStream.range(0, 6).mapToObj(_ -> (Future<String>) pool.submit(() -> {
        try {
          wallet.redeem(u, 6, UUID.randomUUID());
          ok.incrementAndGet();
        } catch (ApiException expected) {
          // not enough coins left
        }
      })).toList();
      for (var f : fs) f.get();
    }
    assertThat(ok).hasValue(1);
    assertThat(wallet.balance(u)).isEqualTo(4);
  }

  @Test
  @SuppressWarnings("unchecked")
  void customerSeesBalanceRulesAndLedgerOverHttp() {
    String email = freshEmail();
    String t = token(email);
    UUID u = users.findByEmail(email).orElseThrow().id();
    wallet.credit(u, 30, UUID.randomUUID());

    Map<String, Object> w = get("/api/v1/wallet", t).getBody();
    assertThat(w.get("coins")).isEqualTo(30);
    assertThat(((Number) w.get("valuePaise")).longValue()).isEqualTo(30 * ((Number) w.get("paisePerCoin")).longValue());
    assertThat((List<Object>) w.get("rules")).isNotEmpty();

    Map<String, Object> ledger = get("/api/v1/wallet/ledger?size=5", t).getBody();
    assertThat((List<Map<String, Object>>) ledger.get("items")).singleElement()
        .satisfies(r -> assertThat(r.get("kind")).isEqualTo("EARN"));
  }

  @Test
  void adminManagesRulesAndSettings() {
    String admin = adminToken();
    Map<String, Object> body = Map.of("name", "Rule-" + UUID.randomUUID(), "spendPaise", 50000, "coins", 7,
        "mode", "PRO_RATA", "minOrderPaise", 10000);
    var created = post("/api/v1/admin/coin-rules", admin, body);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) created.getBody().get("id");
    assertThat(created.getBody()).containsEntry("coins", 7).containsEntry("mode", "PRO_RATA").containsEntry("active", true);
    assertThat(getList("/api/v1/admin/coin-rules", admin)).anyMatch(r -> id.equals(r.get("id")));

    var upd = call(HttpMethod.PUT, "/api/v1/admin/coin-rules/" + id, admin,
        Map.of("name", "Renamed", "spendPaise", 100000, "coins", 12, "mode", "PRO_RATA", "active", false), null);
    assertThat(upd.getBody()).containsEntry("name", "Renamed").containsEntry("coins", 12).containsEntry("active", false);

    assertThat(call(HttpMethod.PUT, "/api/v1/admin/coin-rules/" + UUID.randomUUID(), admin, body, null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/coin-rules/" + id, admin, null, null).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(getList("/api/v1/admin/coin-rules", admin)).noneMatch(r -> id.equals(r.get("id")));
  }

  @Test
  void ruleValidationRejectsZeroSpendAndMissingName() {
    String admin = adminToken();
    assertThat(post("/api/v1/admin/coin-rules", admin, Map.of("name", "x", "spendPaise", 0, "coins", 1, "mode", "PRO_RATA"))
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/coin-rules", admin, Map.of("name", "", "spendPaise", 10, "coins", 1, "mode", "PRO_RATA"))
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void settingsAreValidatedAndSaved() {
    String admin = adminToken();
    var current = get("/api/v1/admin/coin-settings", admin).getBody();
    try {
      var ok = call(HttpMethod.PUT, "/api/v1/admin/coin-settings", admin,
          Map.of("paisePerCoin", 50, "maxRedeemPercent", 40, "expiryMonths", 6), null);
      assertThat(ok.getBody()).containsEntry("paisePerCoin", 50).containsEntry("maxRedeemPercent", 40).containsEntry("expiryMonths", 6);
      for (Map<String, Object> bad : List.of(
          Map.<String, Object>of("paisePerCoin", 0, "maxRedeemPercent", 10, "expiryMonths", 1),
          Map.<String, Object>of("paisePerCoin", 100, "maxRedeemPercent", 101, "expiryMonths", 1),
          Map.<String, Object>of("paisePerCoin", 100, "maxRedeemPercent", -1, "expiryMonths", 1),
          Map.<String, Object>of("paisePerCoin", 100, "maxRedeemPercent", 10, "expiryMonths", 0))) {
        assertThat(call(HttpMethod.PUT, "/api/v1/admin/coin-settings", admin, bad, null).getStatusCode())
            .as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
      }
    } finally {
      call(HttpMethod.PUT, "/api/v1/admin/coin-settings", admin, current, null);
    }
  }
}
