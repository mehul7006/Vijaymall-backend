package com.vijay.mall.payment.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.infra.PaymentRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final PaymentService payments;

  public PaymentController(PaymentService payments) { this.payments = payments; }

  @GetMapping("/payment-methods")
  public List<PaymentRepository.Setting> methods() { return payments.enabledMethods(); }

  /** Raw body is needed to check the signature, so it is taken as a String, not parsed. */
  @PostMapping("/payments/webhook/{provider}")
  public ResponseEntity<Void> webhook(@PathVariable String provider, @RequestBody String body,
                                      @RequestHeader HttpHeaders headers) {
    Map<String, String> flat = headers.asMultiValueMap().entrySet().stream().collect(java.util.stream.Collectors.toMap(
        e -> e.getKey().toLowerCase(), e -> e.getValue().stream().findFirst().orElse(""), (a, _) -> a));
    payments.handleWebhook(provider, body, flat);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/admin/payment-methods")
  public List<PaymentRepository.Setting> adminMethods() { return payments.allMethods(); }

  @PutMapping("/admin/payment-methods")
  public List<PaymentRepository.Setting> save(@RequestBody List<PaymentRepository.Setting> in) {
    return payments.saveMethods(in, CurrentUser.id());
  }
}
