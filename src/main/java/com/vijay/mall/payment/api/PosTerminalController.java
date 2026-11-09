package com.vijay.mall.payment.api;

import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.payment.app.TerminalService;
import com.vijay.mall.payment.app.TerminalService.SessionView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Counter staff (CASHIER and ADMIN, see SecurityConfig): ask the chosen card machine to collect an amount and follow the result. The
 * machine is the channel the cashier picked from {@code GET /pos/payment-channels}; the same calls serve every vendor.
 */
@RestController
@RequestMapping("/api/v1/pos/terminal/payments")
public class PosTerminalController {
  private final TerminalService terminal;

  /** Creates the controller. */
  public PosTerminalController(TerminalService terminal) { this.terminal = terminal; }

  /** Request body of a new terminal payment; {@code moneyAccountId} is the chosen channel's account (the machine to use). */
  public record StartBody(long amountPaise, String reference, UUID moneyAccountId) {}

  /** Request body of the cashier's confirmation. */
  public record ConfirmBody(String approvalCode, String rrn) {}

  /**
   * Asks the chosen machine to collect the amount. A repeat is harmless because the reference is the idempotency key: the same reference and
   * amount return the first session. A request with an Idempotency-Key and no reference uses a reference made from the key. This call is deliberately
   * not wrapped in a database transaction, because a Paytm machine is called over the network while it runs.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SessionView start(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody StartBody b) {
    String reference = (b.reference() == null || b.reference().isBlank()) && key != null && !key.isBlank() ? "KEY-" + AdminOps.fingerprint(key).substring(0, 24) : b.reference();
    return terminal.request(CurrentUser.id(), b.amountPaise(), reference, b.moneyAccountId());
  }

  /** The session as it stands; poll it until state is not PENDING. */
  @GetMapping("/{id}")
  public SessionView get(@PathVariable UUID id) { return terminal.get(id); }

  /** The cashier confirms the machine approved (manual terminals only). */
  @PostMapping("/{id}/confirm")
  public SessionView confirm(@PathVariable UUID id, @RequestBody ConfirmBody b) { return terminal.confirm(CurrentUser.id(), id, b.approvalCode(), b.rrn()); }

  /** Voids a paid payment taken the same day (Paytm machines). */
  @PostMapping("/{id}/void")
  public SessionView voidPayment(@PathVariable UUID id) { return terminal.voidPayment(CurrentUser.id(), id); }

  /** Cancels a payment that is still pending. */
  @PostMapping("/{id}/cancel")
  public SessionView cancel(@PathVariable UUID id) { return terminal.cancel(CurrentUser.id(), id); }
}
