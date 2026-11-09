package com.vijay.mall.wallet.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinMode;
import com.vijay.mall.wallet.domain.CoinRule;
import com.vijay.mall.wallet.domain.CoinSettings;
import com.vijay.mall.wallet.domain.StoredRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminWalletController {
  private com.vijay.mall.common.idem.AdminOps ops;

  /** Makes repeats of an admin action with the same Idempotency-Key harmless (plain unit tests build the controller without it). */
  @org.springframework.beans.factory.annotation.Autowired
  void setOps(com.vijay.mall.common.idem.AdminOps ops) { this.ops = ops; }

  public record RuleBody(@NotBlank String name, @Min(1) long spendPaise, @Min(1) long coins, @NotNull CoinMode mode,
                         @Min(0) Long minOrderPaise, Instant startAt, Instant endAt, Boolean active) {
    StoredRule toStored() {
      return new StoredRule(null, new CoinRule(name, spendPaise, coins, mode, minOrderPaise == null ? 0 : minOrderPaise),
          startAt, endAt, active == null || active);
    }
  }

  @Schema(name = "CoinRuleView")

  public record RuleView(UUID id, String name, long spendPaise, long coins, String mode, long minOrderPaise,
                         Instant startAt, Instant endAt, boolean active) {
    static RuleView of(StoredRule s) {
      CoinRule r = s.rule();
      return new RuleView(s.id(), r.name(), r.spendPaise(), r.coins(), r.mode().name(), r.minOrderPaise(),
          s.startAt(), s.endAt(), s.active());
    }
  }

  private final WalletService wallet;

  public AdminWalletController(WalletService wallet) { this.wallet = wallet; }

  @GetMapping("/coin-rules")
  public List<RuleView> rules() { return wallet.rules().stream().map(RuleView::of).toList(); }

  @PostMapping("/coin-rules")
  @ResponseStatus(HttpStatus.CREATED)
  public RuleView create(@Valid @RequestBody RuleBody b, @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return com.vijay.mall.common.idem.AdminOps.run(ops, key, "coin-rule-create", CurrentUser.id(), com.vijay.mall.common.idem.AdminOps.fingerprint(b),
        () -> RuleView.of(wallet.createRule(b.toStored(), CurrentUser.id())), v -> v.id().toString(),
        ref -> wallet.rules().stream().filter(r -> r.id().toString().equals(ref)).findFirst().map(RuleView::of).orElse(null));
  }

  @PutMapping("/coin-rules/{id}")
  public RuleView update(@PathVariable UUID id, @Valid @RequestBody RuleBody b) {
    return RuleView.of(wallet.updateRule(id, b.toStored(), CurrentUser.id()));
  }

  @DeleteMapping("/coin-rules/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) { wallet.deleteRule(id, CurrentUser.id()); }

  @GetMapping("/coin-settings")
  public CoinSettings settings() { return wallet.settings(); }

  @PutMapping("/coin-settings")
  public CoinSettings save(@RequestBody CoinSettings s) { return wallet.saveSettings(s, CurrentUser.id()); }
}
