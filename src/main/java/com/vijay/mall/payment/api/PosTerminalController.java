package com.vijay.mall.payment.api;

import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.payment.app.TerminalService;
import com.vijay.mall.payment.infra.TerminalPaymentRepository.Row;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Counter staff (CASHIER and ADMIN, see SecurityConfig): ask the card machine to collect an amount and follow the result. */
@RestController
@RequestMapping("/api/v1/pos/terminal/payments")
public class PosTerminalController {
  private final TerminalService terminal;
  private AdminOps ops;

  /** Makes repeats with the same Idempotency-Key harmless (plain unit tests build the controller without it). */
  @Autowired(required = false)
  void setOps(AdminOps ops) { this.ops = ops; }

  /** Creates the controller. */
  public PosTerminalController(TerminalService terminal) { this.terminal = terminal; }

  /** Request body of a new terminal payment. */
  public record StartBody(long amountPaise, String reference, UUID moneyAccountId) {}

  /** Request body of the cashier's confirmation. */
  public record ConfirmBody(String approvalCode, String rrn) {}

  /** Asks the terminal to collect the amount. With an Idempotency-Key (or the same reference) a repeat returns the first session. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Row start(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody StartBody b) {
    UUID actor = CurrentUser.id();
    return AdminOps.run(ops, key, "terminal-payment", actor, AdminOps.fingerprint(b), () -> terminal.request(actor, b.amountPaise(), b.reference(), b.moneyAccountId()),
        r -> r.id().toString(), id -> terminal.get(UUID.fromString(id)));
  }

  /** The session as it stands; poll it until state is not PENDING. */
  @GetMapping("/{id}")
  public Row get(@PathVariable UUID id) { return terminal.get(id); }

  /** The cashier confirms the machine approved (manual terminals only). */
  @PostMapping("/{id}/confirm")
  public Row confirm(@PathVariable UUID id, @RequestBody ConfirmBody b) { return terminal.confirm(CurrentUser.id(), id, b.approvalCode(), b.rrn()); }

  /** Cancels a payment that is still pending. */
  @PostMapping("/{id}/cancel")
  public Row cancel(@PathVariable UUID id) { return terminal.cancel(CurrentUser.id(), id); }
}
