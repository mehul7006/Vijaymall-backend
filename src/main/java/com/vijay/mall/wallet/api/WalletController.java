package com.vijay.mall.wallet.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.common.ReferenceNumbers;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinSettings;
import com.vijay.mall.wallet.domain.StoredRule;
import com.vijay.mall.wallet.infra.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's coins under {@code /api/v1/wallet}: balance, how the coins are earned, and the history.
 * Any signed-in user may call it.
 */
@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {
  /**
   * An active earning rule, shown so the customer knows how coins are earned.
   * @param name rule name
   * @param spendPaise the spend step in paise
   * @param coins coins given per step
   * @param mode {@code PRO_RATA} or {@code SLAB}
   */
  @Schema(name = "WalletRule")
  public record RuleView(String name, long spendPaise, long coins, String mode) {}

  /**
   * The customer's wallet.
   * @param coins current balance
   * @param valuePaise what the balance is worth in paise
   * @param paisePerCoin value of one coin in paise
   * @param maxRedeemPercent the largest share of an order that coins may pay, in percent
   * @param rules the earning rules currently active
   */
  public record WalletView(int coins, long valuePaise, long paisePerCoin, int maxRedeemPercent, List<RuleView> rules) {}

  /**
   * One line of the coin history.
   * @param id ledger row id
   * @param kind {@code EARN}, {@code REDEEM}, {@code REVERSE}, {@code ADJUST} or {@code EXPIRE}
   * @param coins signed change in coins
   * @param orderNo the order, bill or return number behind the entry, null if none
   * @param createdAt when it happened
   * @param expiresAt when earned coins expire, null otherwise
   */
  public record LedgerView(UUID id, String kind, int coins, String orderNo, Instant createdAt, Instant expiresAt) {}

  private final WalletService wallet;
  private final ReferenceNumbers references;
  private final Clock clock;

  /** Creates the controller (wired by Spring). */
  public WalletController(WalletService wallet, ReferenceNumbers references, Clock clock) {
    this.wallet = wallet;
    this.references = references;
    this.clock = clock;
  }

  /** Returns the balance, its rupee value and the active earning rules. */
  @GetMapping
  public WalletView get() {
    UUID u = CurrentUser.id();
    CoinSettings s = wallet.settings();
    int coins = wallet.balance(u);
    List<RuleView> rules = wallet.activeRules(clock.instant()).stream()
        .map(r -> new RuleView(r.name(), r.spendPaise(), r.coins(), r.mode().name())).toList();
    return new WalletView(coins, coins * s.paisePerCoin(), s.paisePerCoin(), s.maxRedeemPercent(), rules);
  }

  /** Returns a page of the coin history, newest first. {@code page} starts at 0; the default size is 20. */
  @GetMapping("/ledger")
  public Page<LedgerView> ledger(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
    Page<WalletRepository.LedgerRow> p = wallet.ledger(CurrentUser.id(), page, size);
    // orderId can also be a counter bill or a return, so every kind of number is looked up
    Map<UUID, String> numbers = references.of(p.items().stream().map(WalletRepository.LedgerRow::orderId)
        .filter(Objects::nonNull).distinct().toList());
    List<LedgerView> items = p.items().stream().map(r -> new LedgerView(r.id(), r.kind(), r.coins(),
        r.orderId() == null ? null : numbers.get(r.orderId()), r.createdAt(), r.expiresAt())).toList();
    return new Page<>(items, p.page(), p.size(), p.total());
  }
}
