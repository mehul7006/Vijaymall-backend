package com.vijay.mall.wallet.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.wallet.app.OrderNumberLookup;
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

@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {
  @Schema(name = "WalletRule")
  public record RuleView(String name, long spendPaise, long coins, String mode) {}

  public record WalletView(int coins, long valuePaise, long paisePerCoin, int maxRedeemPercent, List<RuleView> rules) {}

  public record LedgerView(UUID id, String kind, int coins, String orderNo, Instant createdAt, Instant expiresAt) {}

  private final WalletService wallet;
  private final OrderNumberLookup orders;
  private final Clock clock;

  public WalletController(WalletService wallet, OrderNumberLookup orders, Clock clock) {
    this.wallet = wallet;
    this.orders = orders;
    this.clock = clock;
  }

  @GetMapping
  public WalletView get() {
    UUID u = CurrentUser.id();
    CoinSettings s = wallet.settings();
    int coins = wallet.balance(u);
    List<RuleView> rules = wallet.activeRules(clock.instant()).stream()
        .map(r -> new RuleView(r.name(), r.spendPaise(), r.coins(), r.mode().name())).toList();
    return new WalletView(coins, coins * s.paisePerCoin(), s.paisePerCoin(), s.maxRedeemPercent(), rules);
  }

  @GetMapping("/ledger")
  public Page<LedgerView> ledger(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
    Page<WalletRepository.LedgerRow> p = wallet.ledger(CurrentUser.id(), page, size);
    Map<UUID, String> numbers = orders.orderNumbers(p.items().stream().map(WalletRepository.LedgerRow::orderId)
        .filter(Objects::nonNull).distinct().toList());
    List<LedgerView> items = p.items().stream().map(r -> new LedgerView(r.id(), r.kind(), r.coins(),
        r.orderId() == null ? null : numbers.get(r.orderId()), r.createdAt(), r.expiresAt())).toList();
    return new Page<>(items, p.page(), p.size(), p.total());
  }
}
