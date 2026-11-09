package com.vijay.mall.payment.paytm.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.payment.paytm.app.PaymentTerminalService;
import com.vijay.mall.payment.paytm.app.PaymentTerminalService.TerminalInput;
import com.vijay.mall.payment.paytm.app.PaymentTerminalService.TerminalView;
import com.vijay.mall.payment.paytm.domain.PaytmTerminalService.ConnectionResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Card machines master, admin only (everything under /admin). */
@RestController
@RequestMapping("/api/v1/admin/payment-terminals")
public class AdminPaymentTerminalController {
  /**
   * Body of add and change.
   * @param terminalId the Paytm terminal id, 8 letters or digits (needed to add; ignored on change)
   * @param name the name shown at the till
   * @param active whether the till may use it
   * @param settlesToAccountId the BANK money account the machine's account pays out into (optional)
   */
  @Schema(name = "PaymentTerminalBody")
  public record Body(@Size(max = 20) String terminalId, @NotBlank @Size(max = 60) String name, Boolean active, UUID settlesToAccountId) {}

  private final PaymentTerminalService service;

  /** Creates the controller. */
  public AdminPaymentTerminalController(PaymentTerminalService service) { this.service = service; }

  /** One page of machines, newest first. */
  @GetMapping
  public Page<TerminalView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return service.list(page, size);
  }

  /** Adds a machine and its money account (201). 409 {@code DUPLICATE_TERMINAL} when the terminal id exists. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TerminalView create(@Valid @RequestBody Body b) {
    return service.create(CurrentUser.id(), new TerminalInput(b.terminalId(), b.name(), b.active(), b.settlesToAccountId()));
  }

  /** Renames a machine, switches it on or off, sets the bank its account pays out into. */
  @PutMapping("/{id}")
  public TerminalView update(@PathVariable UUID id, @Valid @RequestBody Body b) {
    return service.update(CurrentUser.id(), id, new TerminalInput(b.terminalId(), b.name(), b.active(), b.settlesToAccountId()));
  }

  /** Switches a machine off. */
  @PostMapping("/{id}/deactivate")
  public TerminalView deactivate(@PathVariable UUID id) { return service.deactivate(CurrentUser.id(), id); }

  /** Test button: proves signature, merchant id and terminal without moving money. */
  @PostMapping("/{id}/test")
  public ConnectionResult test(@PathVariable UUID id) { return service.test(CurrentUser.id(), id); }
}
