package com.vijay.mall.wallet.api;

import com.vijay.mall.common.Limits;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinMode;
import com.vijay.mall.wallet.domain.CoinRule;
import com.vijay.mall.wallet.domain.CoinSettings;
import com.vijay.mall.wallet.domain.StoredRule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin screens for the loyalty coins: earning rules and the shop-wide coin settings, under {@code /api/v1/admin}.
 * Admin only (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminWalletController {
  private AdminOps ops;

  /** Makes repeats of an admin action with the same Idempotency-Key harmless (plain unit tests build the controller without it). */
  @Autowired
  void setOps(AdminOps ops) { this.ops = ops; }

  /**
   * Request body for creating or changing an earning rule. All money is in paise.
   * @param name rule name shown to customers (at most 120 characters)
   * @param spendPaise the spend step, at least 1
   * @param coins coins given for that spend, at least 1
   * @param mode {@code PRO_RATA} or {@code SLAB}
   * @param minOrderPaise minimum order value for the rule to apply; null means none
   * @param startAt first moment the rule is live; null means no start limit
   * @param endAt last moment the rule is live; null means no end limit
   * @param active whether the rule is switched on; null means true
   */
  public record RuleBody(@NotBlank @Size(max = Limits.NAME) String name, @Min(1) @Max(Limits.MAX_PRICE_PAISE) long spendPaise,
                         @Min(1) @Max(Limits.MAX_QTY) long coins, @NotNull CoinMode mode,
                         @Min(0) @Max(Limits.MAX_PRICE_PAISE) Long minOrderPaise, Instant startAt, Instant endAt, Boolean active) {
    StoredRule toStored() {
      return new StoredRule(null, new CoinRule(name, spendPaise, coins, mode, minOrderPaise == null ? 0 : minOrderPaise),
          startAt, endAt, active == null || active);
    }
  }

  /**
   * An earning rule as the admin sees it.
   * @param id rule id
   * @param mode {@code PRO_RATA} or {@code SLAB}
   * @param minOrderPaise minimum order value in paise, 0 for none
   * @param active whether the rule is switched on
   */
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

  /** Creates the controller with the wallet service (wired by Spring). */
  public AdminWalletController(WalletService wallet) { this.wallet = wallet; }

  /** Lists every earning rule, by name. */
  @GetMapping("/coin-rules")
  public List<RuleView> rules() { return wallet.rules().stream().map(RuleView::of).toList(); }

  /**
   * Adds an earning rule (201). Repeating the call with the same {@code Idempotency-Key} header returns the same rule
   * instead of creating a second one.
   */
  @PostMapping("/coin-rules")
  @ResponseStatus(HttpStatus.CREATED)
  public RuleView create(@Valid @RequestBody RuleBody b, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return AdminOps.run(ops, key, "coin-rule-create", CurrentUser.id(), AdminOps.fingerprint(b),
        () -> RuleView.of(wallet.createRule(b.toStored(), CurrentUser.id())), v -> v.id().toString(),
        ref -> wallet.rules().stream().filter(r -> r.id().toString().equals(ref)).findFirst().map(RuleView::of).orElse(null));
  }

  /** Changes an earning rule. Fails with 404 when the rule does not exist. */
  @PutMapping("/coin-rules/{id}")
  public RuleView update(@PathVariable UUID id, @Valid @RequestBody RuleBody b) {
    return RuleView.of(wallet.updateRule(id, b.toStored(), CurrentUser.id()));
  }

  /** Deletes an earning rule. */
  @DeleteMapping("/coin-rules/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) { wallet.deleteRule(id, CurrentUser.id()); }

  /**
   * Returns the coin settings: paise per coin, the maximum share of an order payable in coins, and the expiry in months.
   */
  @GetMapping("/coin-settings")
  public CoinSettings settings() { return wallet.settings(); }

  /**
   * Saves the coin settings. Fails with 422 when a value is out of range (see {@link WalletService#saveSettings}).
   */
  @PutMapping("/coin-settings")
  public CoinSettings save(@Valid @RequestBody CoinSettings s) { return wallet.saveSettings(s, CurrentUser.id()); }
}
