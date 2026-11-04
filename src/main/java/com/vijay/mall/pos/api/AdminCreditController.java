package com.vijay.mall.pos.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.CreditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin side of credit: who may buy on credit and up to how much, corrections and write-offs. Lists are in reports. */
@RestController
@RequestMapping("/api/v1/admin/credit")
public class AdminCreditController {
  public record LimitBody(@PositiveOrZero long limitPaise) {}

  /** Approves credit for a customer by mobile number, creating the customer if the shop has not seen them yet. */
  public record MobileLimitBody(@NotBlank String mobile, @PositiveOrZero long limitPaise) {}

  /** amountPaise below 0 lowers what the customer owes (a write-off); above 0 raises it. A note is required. */
  public record AdjustBody(long amountPaise, @NotBlank @Size(max = 200) String note) {}

  private final CreditService credit;

  public AdminCreditController(CreditService credit) { this.credit = credit; }

  @PutMapping("/limit")
  public CreditService.AccountView approve(@Valid @RequestBody MobileLimitBody body) {
    return credit.setLimitByMobile(body.mobile(), body.limitPaise(), CurrentUser.id());
  }

  @GetMapping("/accounts/{customerId}")
  public CreditService.AccountView get(@PathVariable UUID customerId) { return credit.account(customerId); }

  @GetMapping("/accounts/{customerId}/ledger")
  public Page<CreditService.LedgerView> ledger(@PathVariable UUID customerId, @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
    return credit.ledger(customerId, page, size);
  }

  /** 0 switches credit off for this customer. */
  @PutMapping("/accounts/{customerId}/limit")
  public CreditService.AccountView setLimit(@PathVariable UUID customerId, @Valid @RequestBody LimitBody body) {
    return credit.setLimit(customerId, body.limitPaise(), CurrentUser.id());
  }

  @PostMapping("/accounts/{customerId}/adjust")
  public CreditService.AccountView adjust(@PathVariable UUID customerId, @Valid @RequestBody AdjustBody body) {
    return credit.adjust(customerId, body.amountPaise(), body.note(), CurrentUser.id());
  }
}
