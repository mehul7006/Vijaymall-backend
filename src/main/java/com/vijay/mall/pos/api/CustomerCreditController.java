package com.vijay.mall.pos.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.CreditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A customer sees what they owe the shop (credit taken at the counter) and every change to it. Read only. */
@RestController
@RequestMapping("/api/v1/credit")
public class CustomerCreditController {
  private final CreditService credit;

  public CustomerCreditController(CreditService credit) { this.credit = credit; }

  @GetMapping
  public CreditService.AccountView account() { return credit.account(CurrentUser.id()); }

  @GetMapping("/ledger")
  public Page<CreditService.LedgerView> ledger(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
    return credit.ledger(CurrentUser.id(), page, size);
  }
}
