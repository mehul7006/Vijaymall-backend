package com.vijay.mall.pos.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.CreditService;
import com.vijay.mall.pos.app.PosViews.Pay;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credit at the counter (open to CASHIER and ADMIN, see SecurityConfig): look a customer up before putting a bill on
 * credit, and take repayments. Putting a bill on credit itself is a CREDIT line on {@code POST /pos/sales}.
 */
@RestController
@RequestMapping("/api/v1/pos/credit")
public class PosCreditController {
  /**
   * Request body for a credit repayment: the customer's mobile, how they pay (CASH, UPI or CARD, split allowed) and
   * an optional note.
   */
  public record RepaymentBody(@NotBlank String customerMobile, @NotEmpty List<Pay> payments, @Size(max = 200) String note) {}

  private final CreditService credit;

  /**
   * Creates the controller.
   *
   * @param credit credit (udhar) account service
   */
  public PosCreditController(CreditService credit) { this.credit = credit; }

  /** What the customer owes and may still take on credit. 404 if the shop has never seen this number. */
  @GetMapping("/account")
  public CreditService.AccountView account(@RequestParam String mobile) { return credit.accountByMobile(mobile); }

  /** Lists a customer's credit ledger by mobile number, newest first. */
  @GetMapping("/ledger")
  public Page<CreditService.LedgerView> ledger(@RequestParam String mobile, @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
    return credit.ledger(credit.accountByMobile(mobile).customerId(), page, size);
  }

  /** The customer pays some or all of what they owe, in CASH / UPI / CARD (split allowed). */
  @PostMapping("/payments")
  @ResponseStatus(HttpStatus.CREATED)
  public CreditService.Receipt collect(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RepaymentBody body) {
    return credit.collect(CurrentUser.id(), key, body.customerMobile(), body.payments(), body.note());
  }
}
