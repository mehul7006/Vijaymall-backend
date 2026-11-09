package com.vijay.mall.payment.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.infra.PaymentRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

/**
 * Payment endpoints: the methods a customer can pick at checkout, the provider webhook, and the admin switches for the methods.
 * <p>The webhook is public (it is authenticated by the provider's signature); the {@code /admin} routes are ADMIN only.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final PaymentService payments;

  /** Creates the controller. */
  public PaymentController(PaymentService payments) { this.payments = payments; }

  /**
   * Payment methods customers may choose now: switched on by the admin and, for {@code ONLINE}, with provider keys set. Public to signed-in customers.
   */
  @GetMapping("/payment-methods")
  public List<PaymentRepository.Setting> methods() { return payments.enabledMethods(); }

  /** Raw body is needed to check the signature, so it is taken as a String, not parsed. */
  @PostMapping("/payments/webhook/{provider}")
  public ResponseEntity<Void> webhook(@PathVariable String provider, @RequestBody String body,
                                      @RequestHeader HttpHeaders headers) {
    Map<String, String> flat = headers.asMultiValueMap().entrySet().stream().collect(Collectors.toMap(
        e -> e.getKey().toLowerCase(), e -> e.getValue().stream().findFirst().orElse(""), (a, _) -> a));
    payments.handleWebhook(provider, body, flat);
    return ResponseEntity.ok().build();
  }

  /** Both payment methods with their switch and whether they are set up (ADMIN). */
  @GetMapping("/admin/payment-methods")
  public List<PaymentService.AdminMethod> adminMethods() { return payments.adminMethods(); }

  /**
   * Switches methods on or off (ADMIN). Online cannot be switched on before the provider keys are set ({@code 422 PAYMENT_NOT_CONFIGURED}) and at least one method must stay on. Returns the new state.
   */
  @PutMapping("/admin/payment-methods")
  public List<PaymentService.AdminMethod> save(@RequestBody List<PaymentRepository.Setting> in) {
    payments.saveMethods(in, CurrentUser.id());
    return payments.adminMethods();
  }
}
