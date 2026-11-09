package com.vijay.mall.customers.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.customers.app.CustomerAdminService.CustomerView;
import com.vijay.mall.customers.app.CustomerAdminService;
import com.vijay.mall.order.domain.OrderViews;
import com.vijay.mall.wallet.infra.WalletRepository;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin). */
@RestController
@RequestMapping("/api/v1/admin/customers")
public class AdminCustomerController {
  private AdminOps ops;

  /** Makes repeats of an admin action with the same Idempotency-Key harmless (plain unit tests build the controller without it). */
  @Autowired
  void setOps(AdminOps ops) { this.ops = ops; }

  /**
   * Request body to switch an account on or off.
   * @param active false to block the customer, true to unblock
   */
  @Schema(name = "CustomerActiveBody")
  public record ActiveBody(@NotNull Boolean active) {}

  /**
   * Request body for a manual coin correction.
   * @param delta coins to give (above 0) or take back (below 0); the wallet allows 1 to 100000 either way
   * @param reason why, 1 to 100 characters, kept in the audit trail
   */
  public record CoinsBody(int delta, String reason) {}

  private final CustomerAdminService customers;

  /** Creates the controller (wired by Spring). */
  public AdminCustomerController(CustomerAdminService customers) { this.customers = customers; }

  /** q matches part of a name, e-mail or mobile number; leave it out for everyone, newest first. */
  @GetMapping
  public Page<CustomerAdminService.CustomerRow> search(@Parameter(schema = @Schema(maxLength = 100)) @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
    return customers.search(q, page, size);
  }

  /**
   * Returns one customer with coins, credit owed and open orders. Fails with 404 for an unknown id or a staff account.
   */
  @GetMapping("/{id}")
  public CustomerView get(@PathVariable UUID id) { return customers.get(id); }

  /** The customer's coin history (kind, coins, orderId, createdAt, expiresAt), newest first; size is clamped to 1..50. */
  @GetMapping("/{id}/wallet-ledger")
  public Page<WalletRepository.LedgerRow> walletLedger(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
    return customers.walletLedger(id, page, size);
  }

  /** The customer's orders, newest first, as the admin order list rows; size is clamped to 1..50. */
  @GetMapping("/{id}/orders")
  public Page<OrderViews.AdminSummary> orders(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
    return customers.orders(id, page, size);
  }

  /** Switch the account off (also signs them out everywhere) or back on. */
  @PatchMapping("/{id}")
  public CustomerView setActive(@PathVariable UUID id, @Valid @RequestBody ActiveBody b) { return customers.setActive(id, b.active(), CurrentUser.id()); }

  /**
   * delta above 0 gives coins, below 0 takes them back; a reason is required. A coin validation error is reported under both
   * {@code details.fields.delta} and (for old clients) {@code details.fields.coins}.
   */
  @PostMapping("/{id}/coins")
  public CustomerView coins(@PathVariable UUID id, @Valid @RequestBody CoinsBody b, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return AdminOps.run(ops, key, "coins-adjust:" + id, CurrentUser.id(), AdminOps.fingerprint(id, b),
        () -> customers.adjustCoins(id, b.delta(), b.reason(), CurrentUser.id()), v -> id.toString(), ref -> customers.get(id));
  }
}
