package com.vijay.mall.payment.api;

import com.vijay.mall.payment.app.MoneyAccountService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Counter staff (CASHIER and ADMIN): the vendors and accounts the cashier can choose from when taking a payment. */
@RestController
@RequestMapping("/api/v1/pos/payment-channels")
public class PosChannelController {
  private final MoneyAccountService accounts;

  /** Creates the controller. */
  public PosChannelController(MoneyAccountService accounts) { this.accounts = accounts; }

  /**
   * The active channels per mode (CASH, UPI, CARD). Each has the provider, a display name, the terminal id (when the account has one), the
   * money account id and a default flag. The till sends the chosen channel's moneyAccountId and terminalId on the payment line.
   */
  @GetMapping
  public List<MoneyAccountService.Channel> list() { return accounts.channels(); }
}
