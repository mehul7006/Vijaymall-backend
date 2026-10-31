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
  public record LedgerRow(UUID id, String kind, int coins, UUID orderId, Instant createdAt, Instant expiresAt) {}

  public record Mismatch(UUID userId, int balance, long ledgerSum) {}

  public record LotUse(UUID lotId, int coins, Instant lotExpiresAt) {}

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

  public int balance(UUID userId) { return wallets.findById(userId).map(w -> w.coins).orElse(0); }

  @Transactional
  public void addBalance(UUID userId, int delta) {
    wallets.lockByUser(userId).ifPresent(w -> w.coins += delta);
  }

  @Transactional
  public List<LotAllocator.Lot> openLotsForUpdate(UUID userId, Instant now) {
    return ledger.openLotsForUpdate(userId, now).stream().map(l -> new LotAllocator.Lot(l.id, l.remaining, l.expiresAt)).toList();
  }

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

  public Optional<UUID> ledgerIdByKey(String idemKey) { return ledger.findByIdemKey(idemKey).map(l -> l.id); }

  @Transactional
  public void insertLotUse(UUID redeemId, UUID lotId, int coins) {
    CoinLotUseEntity u = new CoinLotUseEntity();
    u.redeemId = redeemId;
    u.lotId = lotId;
    u.coins = coins;
    lotUses.save(u);
  }

  public List<LotUse> lotUses(UUID redeemId) {
    List<CoinLotUseEntity> uses = lotUses.findByRedeemId(redeemId);
    Map<UUID, CoinLedgerEntity> lots = ledger.byIds(uses.stream().map(u -> u.lotId).toList()).stream()
        .collect(Collectors.toMap(l -> l.id, Function.identity()));
    return uses.stream().map(u -> new LotUse(u.lotId, u.coins, lots.get(u.lotId).expiresAt)).toList();
  }

  public List<ExpiredLot> expiredLots(Instant now, int limit) {
    return ledger.expired(now, PageRequest.of(0, limit)).stream().map(l -> new ExpiredLot(l.id, l.userId, l.remaining)).toList();
  }

  public int remaining(UUID lotId) { return ledger.findById(lotId).map(l -> l.remaining == null ? 0 : l.remaining).orElse(0); }

  @Transactional
  public void zeroLot(UUID lotId) { ledger.findById(lotId).ifPresent(l -> l.remaining = 0); }

  public Page<LedgerRow> ledger(UUID userId, int page, int size) {
    var result = ledger.findByUserId(userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    List<LedgerRow> rows = result.getContent().stream()
        .map(l -> new LedgerRow(l.id, l.kind, l.coins, l.orderId, l.createdAt, l.expiresAt)).toList();
    return new Page<>(rows, page, size, result.getTotalElements());
  }

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

  public List<StoredRule> rules() { return rules.findAllByOrderByNameAsc().stream().map(WalletRepository::toDomain).toList(); }

  public Optional<StoredRule> rule(UUID id) { return rules.findById(id).map(WalletRepository::toDomain); }

  @Transactional
  public UUID insertRule(StoredRule r) {
    CoinRuleEntity e = new CoinRuleEntity();
    e.id = UUID.randomUUID();
    fill(e, r);
    return rules.save(e).id;
  }

  @Transactional
  public void updateRule(UUID id, StoredRule r) { rules.findById(id).ifPresent(e -> fill(e, r)); }

  @Transactional
  public void deleteRule(UUID id) { rules.deleteById(id); }

  public CoinSettings settings() {
    CoinSettingEntity e = settings.findById(1).orElseThrow();
    return new CoinSettings(e.paisePerCoin, e.maxRedeemPercent, e.expiryMonths);
  }

  @Transactional
  public void saveSettings(CoinSettings s) {
    CoinSettingEntity e = settings.findById(1).orElseThrow();
    e.paisePerCoin = s.paisePerCoin();
    e.maxRedeemPercent = s.maxRedeemPercent();
    e.expiryMonths = s.expiryMonths();
  }
}
