package com.vijay.mall.wallet.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.wallet.domain.CoinMode;
import com.vijay.mall.wallet.domain.CoinRule;
import com.vijay.mall.wallet.domain.CoinSettings;
import com.vijay.mall.wallet.domain.LotAllocator;
import com.vijay.mall.wallet.domain.StoredRule;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage for the wallet module. Balance and lot changes always happen after lockBalance(user), so one customer's
 * requests run one after another and the check-then-insert on the idempotency key is safe.
 */
@Repository
public class WalletRepository {
  /**
   * One line of a customer's coin history.
   * @param id ledger row id
   * @param kind {@code EARN}, {@code REDEEM}, {@code REVERSE}, {@code ADJUST} or {@code EXPIRE}
   * @param coins signed change: positive adds coins, negative removes them
   * @param orderId the order (or counter bill, or return) behind the entry, may be null
   * @param createdAt when it happened
   * @param expiresAt when the coins of an earning expire, null if they never do
   */
  public record LedgerRow(UUID id, String kind, int coins, UUID orderId, Instant createdAt, Instant expiresAt) {}

  /**
   * A wallet whose stored balance differs from the sum of its ledger, found by the nightly check.
   * @param userId the owner
   * @param balance coins in the balance row
   * @param ledgerSum coins according to the ledger
   */
  public record Mismatch(UUID userId, int balance, long ledgerSum) {}

  /**
   * Coins taken from one earned lot by a redemption.
   * @param lotId the earning ledger row the coins came from
   * @param coins how many coins were taken from it
   * @param lotExpiresAt when that lot expires, null if never
   */
  public record LotUse(UUID lotId, int coins, Instant lotExpiresAt) {}

  /**
   * An earned lot past its expiry that still has coins left to remove.
   * @param id the earning ledger row
   * @param userId the owner
   * @param remaining coins still unspent in the lot
   */
  public record ExpiredLot(UUID id, UUID userId, int remaining) {}

  private final WalletJpa wallets;
  private final LedgerJpa ledger;
  private final LotUseJpa lotUses;
  private final RuleJpa rules;
  private final SettingJpa settings;

  WalletRepository(WalletJpa wallets, LedgerJpa ledger, LotUseJpa lotUses, RuleJpa rules, SettingJpa settings) {
    this.wallets = wallets;
    this.ledger = ledger;
    this.lotUses = lotUses;
    this.rules = rules;
    this.settings = settings;
  }

  // ----- balance and lots -----
  /** Creates the balance row if needed, locks it for this transaction and returns the coins. */
  @Transactional
  public int lockBalance(UUID userId) {
    wallets.createIfAbsent(userId);
    return wallets.lockByUser(userId).orElseThrow().coins;
  }

  /** Current coin balance of the user; 0 when they have no wallet row yet. */
  public int balance(UUID userId) { return wallets.findById(userId).map(w -> w.coins).orElse(0); }

  /** Adds (or, when negative, removes) coins on the balance row. Call after {@link #lockBalance}. */
  @Transactional
  public void addBalance(UUID userId, int delta) {
    wallets.lockByUser(userId).ifPresent(w -> w.coins += delta);
  }

  /**
   * Earned lots of the user that still have coins and have not expired, soonest expiry first, locked for this transaction.
   */
  @Transactional
  public List<LotAllocator.Lot> openLotsForUpdate(UUID userId, Instant now) {
    return ledger.openLotsForUpdate(userId, now).stream().map(l -> new LotAllocator.Lot(l.id, l.remaining, l.expiresAt)).toList();
  }

  /** Adds or removes coins from the remaining amount of one earned lot. */
  @Transactional
  public void changeLot(UUID lotId, int delta) {
    ledger.findById(lotId).ifPresent(l -> l.remaining = (l.remaining == null ? 0 : l.remaining) + delta);
  }

  /** Inserts a ledger row; returns false when the idempotency key was already used. */
  @Transactional
  public boolean insertLedger(UUID id, UUID userId, String kind, int coins, Integer remaining, Instant expiresAt,
                              UUID orderId, UUID ruleId, String idemKey, Instant now) {
    if (ledger.existsByIdemKey(idemKey)) return false;
    CoinLedgerEntity e = new CoinLedgerEntity();
    e.id = id;
    e.userId = userId;
    e.kind = kind;
    e.coins = coins;
    e.remaining = remaining;
    e.expiresAt = expiresAt;
    e.orderId = orderId;
    e.ruleId = ruleId;
    e.idemKey = idemKey;
    e.createdAt = now;
    ledger.saveAndFlush(e);
    return true;
  }

  /** Finds the ledger row written for an idempotency key, for example {@code redeem:} plus the order id. */
  public Optional<UUID> ledgerIdByKey(String idemKey) { return ledger.findByIdemKey(idemKey).map(l -> l.id); }

  /** Records that a redemption (or clawback) took this many coins from this lot. */
  @Transactional
  public void insertLotUse(UUID redeemId, UUID lotId, int coins) {
    CoinLotUseEntity u = new CoinLotUseEntity();
    u.redeemId = redeemId;
    u.lotId = lotId;
    u.coins = coins;
    lotUses.save(u);
  }

  /** The lots a redemption took coins from, so a cancelled order can put them back. */
  public List<LotUse> lotUses(UUID redeemId) {
    List<CoinLotUseEntity> uses = lotUses.findByRedeemId(redeemId);
    Map<UUID, CoinLedgerEntity> lots = ledger.byIds(uses.stream().map(u -> u.lotId).toList()).stream()
        .collect(Collectors.toMap(l -> l.id, Function.identity()));
    return uses.stream().map(u -> new LotUse(u.lotId, u.coins, lots.get(u.lotId).expiresAt)).toList();
  }

  /** Up to {@code limit} lots that expired before {@code now} but still hold coins. */
  public List<ExpiredLot> expiredLots(Instant now, int limit) {
    return ledger.expired(now, PageRequest.of(0, limit)).stream().map(l -> new ExpiredLot(l.id, l.userId, l.remaining)).toList();
  }

  /** Coins still unspent in a lot; 0 for an unknown lot. */
  public int remaining(UUID lotId) { return ledger.findById(lotId).map(l -> l.remaining == null ? 0 : l.remaining).orElse(0); }

  /** Sets a lot's remaining coins to zero once it has expired. */
  @Transactional
  public void zeroLot(UUID lotId) { ledger.findById(lotId).ifPresent(l -> l.remaining = 0); }

  /** A page of the user's coin history, newest first. */
  public Page<LedgerRow> ledger(UUID userId, int page, int size) {
    var result = ledger.findByUserId(userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    List<LedgerRow> rows = result.getContent().stream()
        .map(l -> new LedgerRow(l.id, l.kind, l.coins, l.orderId, l.createdAt, l.expiresAt)).toList();
    return new Page<>(rows, page, size, result.getTotalElements());
  }

  /** Wallets whose balance does not equal the sum of their ledger; should always be empty. */
  public List<Mismatch> mismatches() {
    return wallets.mismatches().stream()
        .map(r -> new Mismatch((UUID) r[0], ((Number) r[1]).intValue(), ((Number) r[2]).longValue())).toList();
  }

  // ----- rules and settings -----
  private static StoredRule toDomain(CoinRuleEntity e) {
    return new StoredRule(e.id, new CoinRule(e.name, e.spendPaise, e.coins, CoinMode.valueOf(e.mode), e.minOrderPaise),
        e.startAt, e.endAt, e.active);
  }

  private static void fill(CoinRuleEntity e, StoredRule r) {
    e.name = r.rule().name();
    e.spendPaise = r.rule().spendPaise();
    e.coins = r.rule().coins();
    e.mode = r.rule().mode().name();
    e.minOrderPaise = r.rule().minOrderPaise();
    e.startAt = r.startAt();
    e.endAt = r.endAt();
    e.active = r.active();
  }

  /** All earning rules, by name, whether active or not. */
  public List<StoredRule> rules() { return rules.findAllByOrderByNameAsc().stream().map(WalletRepository::toDomain).toList(); }

  /** One earning rule by id. */
  public Optional<StoredRule> rule(UUID id) { return rules.findById(id).map(WalletRepository::toDomain); }

  /** Saves a new earning rule and returns its id. */
  @Transactional
  public UUID insertRule(StoredRule r) {
    CoinRuleEntity e = new CoinRuleEntity();
    e.id = UUID.randomUUID();
    fill(e, r);
    return rules.save(e).id;
  }

  /** Overwrites an existing earning rule. */
  @Transactional
  public void updateRule(UUID id, StoredRule r) { rules.findById(id).ifPresent(e -> fill(e, r)); }

  /** Deletes an earning rule. */
  @Transactional
  public void deleteRule(UUID id) { rules.deleteById(id); }

  /** The shop-wide coin settings (the single row with id 1). */
  public CoinSettings settings() {
    CoinSettingEntity e = settings.findById(1).orElseThrow();
    return new CoinSettings(e.paisePerCoin, e.maxRedeemPercent, e.expiryMonths);
  }

  /** Overwrites the shop-wide coin settings. */
  @Transactional
  public void saveSettings(CoinSettings s) {
    CoinSettingEntity e = settings.findById(1).orElseThrow();
    e.paisePerCoin = s.paisePerCoin();
    e.maxRedeemPercent = s.maxRedeemPercent();
    e.expiryMonths = s.expiryMonths();
  }
}
