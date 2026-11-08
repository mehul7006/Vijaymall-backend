package com.vijay.mall.payment.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.infra.PaymentRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin): see refunds sent back to customers and retry the ones the payment provider refused. */
@RestController
@RequestMapping("/api/v1/admin/refunds")
public class AdminRefundController {
  private final PaymentService payments;

  public AdminRefundController(PaymentService payments) { this.payments = payments; }

  /** status = PENDING | PROCESSING | ISSUED | FAILED, or leave out for all. FAILED ones need a person. */
  @GetMapping
  public Page<PaymentRepository.RefundRow> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
    return payments.refunds(status, page, size);
  }

  @PostMapping("/{id}/retry")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void retry(@PathVariable UUID id) { payments.retryRefund(id, CurrentUser.id()); }
}
