package com.vijay.mall.customers.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.customers.app.CustomerAdminService;
import com.vijay.mall.customers.app.CustomerAdminService.CustomerView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin). */
@RestController
@RequestMapping("/api/v1/admin/customers")
public class AdminCustomerController {
  public record ActiveBody(boolean active) {}

  public record CoinsBody(int delta, String reason) {}

  private final CustomerAdminService customers;

  public AdminCustomerController(CustomerAdminService customers) { this.customers = customers; }

  /** q matches part of a name, e-mail or mobile number; leave it out for everyone, newest first. */
  @GetMapping
  public Page<CustomerAdminService.CustomerRow> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
    return customers.search(q, page, size);
  }

  @GetMapping("/{id}")
  public CustomerView get(@PathVariable UUID id) { return customers.get(id); }

  /** Switch the account off (also signs them out everywhere) or back on. */
  @PatchMapping("/{id}")
  public CustomerView setActive(@PathVariable UUID id, @RequestBody ActiveBody b) { return customers.setActive(id, b.active(), CurrentUser.id()); }

  /** delta above 0 gives coins, below 0 takes them back; a reason is required. */
  @PostMapping("/{id}/coins")
  public CustomerView coins(@PathVariable UUID id, @Valid @RequestBody CoinsBody b) {
    return customers.adjustCoins(id, b.delta(), b.reason(), CurrentUser.id());
  }
}
