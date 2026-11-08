package com.vijay.mall.wallet.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.wallet.domain.CoinRule;
import com.vijay.mall.wallet.domain.CoinSettings;
import com.vijay.mall.wallet.domain.LotAllocator;
import com.vijay.mall.wallet.domain.StoredRule;
import com.vijay.mall.wallet.infra.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Public interface of the wallet module: balance, rules, redeem, credit, undo, expire. */
@Service
public class WalletService {
  private static final Logger log = LoggerFactory.getLogger(WalletService.class);

  private final WalletRepository repo;
  private final AuditService audit;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;

  /** Without a publisher no live events are sent (used by plain unit tests). */
  public WalletService(WalletRepository repo, AuditService audit, Clock clock, TransactionTemplate tx) {
    this(repo, audit, clock, tx, null);
  }

  @Autowired
  public WalletService(WalletRepository repo, AuditService audit, Clock clock, TransactionTemplate tx,
                       ApplicationEventPublisher events) {
    this.events = events;
    this.tx = tx;
    this.repo = repo;
    this.audit = audit;
    this.clock = clock;
  }

  /** Tells open screens the balance moved, once the surrounding transaction commits. */
  private void changed(UUID userId, String reason, int delta) {
    if (events != null && delta != 0) {
      events.publishEvent(new com.vijay.mall.common.live.WalletChanged(userId, reason, delta, clock.instant()));
    }
  }

  private com.vijay.mall.common.cache.AppCaches caches;

  /** Optional (plain unit tests run without it): coin settings and rules are read on every quote, so they are cached. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setCaches(com.vijay.mall.common.cache.AppCaches caches) { this.caches = caches; }

  private void forget(com.vijay.mall.common.cache.AppCaches.Name name) { if (caches != null) caches.invalidate(name); }

  public CoinSettings settings() {
    return caches == null ? repo.settings() : caches.get(com.vijay.mall.common.cache.AppCaches.Name.COIN_SETTINGS, "1", repo::settings);
  }

  private List<StoredRule> allRules() {
    return caches == null ? repo.rules() : caches.get(com.vijay.mall.common.cache.AppCaches.Name.COIN_RULES, "all", repo::rules);
  }

  public int balance(UUID userId) { return repo.balance(userId); }

  public List<CoinRule> activeRules(Instant now) {
    return allRules().stream().filter(r -> r.isLive(now)).map(StoredRule::rule).toList();
  }

  public Page<WalletRepository.LedgerRow> ledger(UUID userId, int page, int size) {
    return repo.ledger(userId, Math.max(0, page), Page.clampSize(size));
  }

  /** Spends coins for an order, oldest-expiring coins first. Must run inside the order transaction. */
  @Transactional
  public void redeem(UUID userId, int coins, UUID orderId) {
    if (coins <= 0) return;
    Instant now = clock.instant();
    int balance = repo.lockBalance(userId);
    if (balance < coins) throw ApiException.validation("coins", "not enough coins");
    List<LotAllocator.Use> uses = LotAllocator.allocate(repo.openLotsForUpdate(userId, now), coins);
    UUID redeemId = UUID.randomUUID();
    if (!repo.insertLedger(redeemId, userId, "REDEEM", -coins, null, null, orderId, null, "redeem:" + orderId, now)) return;
    for (LotAllocator.Use u : uses) {
      repo.insertLotUse(redeemId, u.lotId(), u.coins());
      repo.changeLot(u.lotId(), -u.coins());
    }
    repo.addBalance(userId, -coins);
    log.info("Coins redeemed user={} order={} coins={}", userId, orderId, coins);
    changed(userId, "REDEEM", -coins);
  }

  /** Credits earned coins once per order (idempotent). */
  @Transactional
  public void credit(UUID userId, int coins, UUID orderId) {
    if (coins <= 0) return;
    Instant now = clock.instant();
    repo.lockBalance(userId);
    Instant expires = now.atZone(ZoneOffset.UTC).plusMonths(settings().expiryMonths()).toInstant();
    if (repo.insertLedger(UUID.randomUUID(), userId, "EARN", coins, coins, expires, orderId, null, "earn:" + orderId, now)) {
      repo.addBalance(userId, coins);
      log.info("Coins credited user={} order={} coins={}", userId, orderId, coins);
      changed(userId, "EARN", coins);
    }
  }

  /** Gives back coins spent on a cancelled order, into their original lots when still valid. */
  @Transactional
  public void reverse(UUID userId, UUID orderId) {
    Instant now = clock.instant();
    var redeemId = repo.ledgerIdByKey("redeem:" + orderId);
    if (redeemId.isEmpty()) return;
    repo.lockBalance(userId);
    int total = 0;
    int refundedToNewLot = 0;
    for (WalletRepository.LotUse u : repo.lotUses(redeemId.get())) {
      total += u.coins();
      if (u.lotExpiresAt() == null || u.lotExpiresAt().isAfter(now)) repo.changeLot(u.lotId(), u.coins());
      else refundedToNewLot += u.coins();
    }
    if (total == 0) return;
    if (!repo.insertLedger(UUID.randomUUID(), userId, "REVERSE", total, null, null, orderId, null, "reverse:" + orderId, now)) return;
    if (refundedToNewLot > 0) {
      repo.insertLedger(UUID.randomUUID(), userId, "ADJUST", 0, refundedToNewLot, now.plusSeconds(30L * 86400), orderId,
          null, "refund-lot:" + orderId, now);
    }
    repo.addBalance(userId, total);
    log.info("Coins reversed user={} order={} coins={}", userId, orderId, total);
    changed(userId, "REVERSE", total);
  }

  /**
   * Takes back coins that were earned on goods that came back. Takes at most what is still unspent in open lots and
   * returns the number actually taken. Idempotent per return.
   */
  @Transactional
  public int clawback(UUID userId, int coins, UUID returnId) {
    if (coins <= 0) return 0;
    Instant now = clock.instant();
    repo.lockBalance(userId);
    List<LotAllocator.Lot> lots = repo.openLotsForUpdate(userId, now);
    int take = Math.min(coins, lots.stream().mapToInt(LotAllocator.Lot::remaining).sum());
    if (take <= 0) return 0;
    UUID ledgerId = UUID.randomUUID();
    if (!repo.insertLedger(ledgerId, userId, "ADJUST", -take, null, null, returnId, null, "clawback:" + returnId, now)) return 0;
    LotAllocator.allocate(lots, take).forEach(u -> {
      repo.insertLotUse(ledgerId, u.lotId(), u.coins());
      repo.changeLot(u.lotId(), -u.coins());
    });
    repo.addBalance(userId, -take);
    log.info("Coins clawed back user={} return={} coins={}", userId, returnId, take);
    changed(userId, "CLAWBACK", -take);
    return take;
  }

  /** Gives back coins spent on goods that came back, as a fresh lot valid for 30 days. Idempotent per return. */
  @Transactional
  public void restore(UUID userId, int coins, UUID returnId) {
    if (coins <= 0) return;
    Instant now = clock.instant();
    repo.lockBalance(userId);
    if (repo.insertLedger(UUID.randomUUID(), userId, "ADJUST", coins, coins, now.plusSeconds(30L * 86400), returnId, null,
        "restore:" + returnId, now)) {
      repo.addBalance(userId, coins);
      log.info("Coins restored user={} return={} coins={}", userId, returnId, coins);
      changed(userId, "RESTORE", coins);
    }
  }

  static final int MAX_ADMIN_ADJUST = 100_000;

  /**
   * A manual correction by the admin (a goodwill gift, or taking back coins given by mistake). A gift gets the normal expiry;
   * a deduction is taken from the coins that expire soonest and can never take more than the customer has.
   * Returns the new balance.
   */
  @Transactional
  public int adminAdjust(UUID userId, int delta, UUID actor, String reason) {
    if (delta == 0 || Math.abs((long) delta) > MAX_ADMIN_ADJUST) {
      throw ApiException.validation("coins", "a non-zero number up to " + MAX_ADMIN_ADJUST);
    }
    Instant now = clock.instant();
    int balance = repo.lockBalance(userId);
    UUID ledgerId = UUID.randomUUID();
    if (delta > 0) {
      Instant expires = now.atZone(ZoneOffset.UTC).plusMonths(settings().expiryMonths()).toInstant();
      repo.insertLedger(ledgerId, userId, "ADJUST", delta, delta, expires, null, null, "admin-adjust:" + ledgerId, now);
      repo.addBalance(userId, delta);
    } else {
      int take = -delta;
      List<LotAllocator.Lot> lots = repo.openLotsForUpdate(userId, now);
      int spendable = lots.stream().mapToInt(LotAllocator.Lot::remaining).sum();
      if (balance < take || spendable < take) {
        throw ApiException.validation("coins", "the customer has only " + Math.min(balance, spendable) + " coins");
      }
      repo.insertLedger(ledgerId, userId, "ADJUST", -take, null, null, null, null, "admin-adjust:" + ledgerId, now);
      for (LotAllocator.Use u : LotAllocator.allocate(lots, take)) {
        repo.insertLotUse(ledgerId, u.lotId(), u.coins());
        repo.changeLot(u.lotId(), -u.coins());
      }
      repo.addBalance(userId, -take);
    }
    audit.log(actor, "COINS_ADJUST", "users", userId, delta + " coins: " + reason);
    log.info("Coins adjusted by admin user={} delta={} actor={}", userId, delta, actor);
    changed(userId, "ADJUST", delta);
    return repo.balance(userId);
  }

  // ---------- jobs ----------
  @Scheduled(cron = "0 0 2 * * *")
  public void expireLots() {
    for (WalletRepository.ExpiredLot e : repo.expiredLots(clock.instant(), 500)) tx.executeWithoutResult(t -> expireOne(e));
  }

  private void expireOne(WalletRepository.ExpiredLot e) {
    repo.lockBalance(e.userId());
    int remaining = repo.remaining(e.id());
    if (remaining <= 0) return;
    Instant now = clock.instant();
    if (repo.insertLedger(UUID.randomUUID(), e.userId(), "EXPIRE", -remaining, null, null, null, null, "expire:" + e.id(), now)) {
      repo.zeroLot(e.id());
      log.info("Coins expired user={} lot={} coins={}", e.userId(), e.id(), remaining);
      int taken = Math.min(remaining, repo.balance(e.userId()));
      repo.addBalance(e.userId(), -taken);
      changed(e.userId(), "EXPIRE", -taken);
    }
  }

  @Scheduled(cron = "0 15 2 * * *")
  public void consistencyCheck() {
    for (WalletRepository.Mismatch m : repo.mismatches()) {
      log.error("Wallet mismatch for user {}: balance {} but ledger sum {}", m.userId(), m.balance(), m.ledgerSum());
    }
  }

  // ---------- admin ----------
  public List<StoredRule> rules() { return repo.rules(); } // the admin always sees the stored truth, never a cached copy

  @Transactional
  public StoredRule createRule(StoredRule in, UUID actor) {
    UUID id = repo.insertRule(in);
    audit.log(actor, "CREATE", "coin_rule", id, in.rule().name());
    forget(com.vijay.mall.common.cache.AppCaches.Name.COIN_RULES);
    return repo.rule(id).orElseThrow();
  }

  @Transactional
  public StoredRule updateRule(UUID id, StoredRule in, UUID actor) {
    repo.rule(id).orElseThrow(() -> ApiException.notFound("Coin rule"));
    repo.updateRule(id, in);
    audit.log(actor, "UPDATE", "coin_rule", id, in.rule().name());
    forget(com.vijay.mall.common.cache.AppCaches.Name.COIN_RULES);
    return repo.rule(id).orElseThrow();
  }

  @Transactional
  public void deleteRule(UUID id, UUID actor) {
    repo.deleteRule(id);
    audit.log(actor, "DELETE", "coin_rule", id, null);
    forget(com.vijay.mall.common.cache.AppCaches.Name.COIN_RULES);
  }

  @Transactional
  public CoinSettings saveSettings(CoinSettings s, UUID actor) {
    if (s.paisePerCoin() <= 0) throw ApiException.validation("paisePerCoin", "must be positive");
    if (s.maxRedeemPercent() < 0 || s.maxRedeemPercent() > 100) throw ApiException.validation("maxRedeemPercent", "0 to 100");
    if (s.expiryMonths() <= 0) throw ApiException.validation("expiryMonths", "must be positive");
    repo.saveSettings(s);
    audit.log(actor, "UPDATE", "coin_setting", 1, s.toString());
    forget(com.vijay.mall.common.cache.AppCaches.Name.COIN_SETTINGS);
    return repo.settings();
  }
}
