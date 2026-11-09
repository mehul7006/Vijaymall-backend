package com.vijay.mall.payment.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.infra.PaymentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin): payments that arrived in the wrong amount or twice and were refunded automatically. */
@RestController
@RequestMapping("/api/v1/admin/payments/anomalies")
public class AdminPaymentAnomalyController {
  private final PaymentService payments;

  public AdminPaymentAnomalyController(PaymentService payments) { this.payments = payments; }

  /** Newest first. kind is MISMATCH (wrong amount) or DUPLICATE (second payment on a paid order). */
  @GetMapping
  public Page<PaymentRepository.AnomalyRow> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return payments.anomalies(page, size);
  }
}
