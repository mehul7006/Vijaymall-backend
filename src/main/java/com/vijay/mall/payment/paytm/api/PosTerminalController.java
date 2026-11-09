package com.vijay.mall.payment.paytm.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.payment.paytm.app.PaymentTerminalService;
import com.vijay.mall.payment.paytm.app.PaymentTerminalService.PosTerminalView;
import com.vijay.mall.payment.paytm.app.TerminalPaymentService;
import com.vijay.mall.payment.paytm.app.TerminalPaymentService.TerminalPaymentView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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

/** The till's side of the card machine: list machines, start, read, cancel and void a card payment. Cashier or admin. */
@RestController
@RequestMapping("/api/v1/pos")
public class PosTerminalController {
  /**
   * Body of a new card payment.
   * @param terminalId our id of the machine (from the terminal list)
   * @param amountPaise amount in paise
   * @param reference the bill number or cart reference
   */
  @Schema(name = "TerminalPaymentStartBody")
  public record StartBody(@NotNull UUID terminalId, @Min(1) @Max(100_000_000) long amountPaise, @Size(max = 50) String reference) {}

  private final PaymentTerminalService terminals;
  private final TerminalPaymentService payments;

  /** Creates the controller. */
  public PosTerminalController(PaymentTerminalService terminals, TerminalPaymentService payments) {
    this.terminals = terminals;
    this.payments = payments;
  }

  /** Active machines for the till. */
  @GetMapping("/terminals")
  public List<PosTerminalView> terminals() { return terminals.activeForTill(); }

  /** Sends the amount to the machine (201). The same Idempotency-Key returns the same payment. */
  @PostMapping("/terminal-payments")
  @ResponseStatus(HttpStatus.CREATED)
  public TerminalPaymentView start(@RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody StartBody b) {
    return payments.start(CurrentUser.id(), key, b.terminalId(), b.amountPaise(), b.reference());
  }

  /** Current state; checks Paytm first when the payment is open and a check is due. */
  @GetMapping("/terminal-payments/{id}")
  public TerminalPaymentView get(@PathVariable UUID id) { return payments.get(id); }

  /** Tries to cancel an unpaid payment (see the guide for the rule). */
  @PostMapping("/terminal-payments/{id}/cancel")
  public TerminalPaymentView cancel(@PathVariable UUID id) { return payments.cancel(CurrentUser.id(), id); }

  /** Voids a paid payment the same day. */
  @PostMapping("/terminal-payments/{id}/void")
  public TerminalPaymentView voidPayment(@PathVariable UUID id) { return payments.voidPayment(CurrentUser.id(), id); }
}
