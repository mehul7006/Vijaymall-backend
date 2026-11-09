package com.vijay.mall.order.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.order.domain.OrderStatus;
import com.vijay.mall.order.domain.OrderViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {
  public record StatusBody(@NotNull OrderStatus status, String note) {}

  public record RejectBody(@NotNull @Size(min = 1, max = 200) String reason) {}

  private final OrderService orders;

  public AdminOrderController(OrderService orders) { this.orders = orders; }

  @GetMapping
  public Page<OrderViews.AdminSummary> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
    return orders.adminList(status, q, page, size);
  }

  @GetMapping("/{id}")
  public OrderViews.AdminView get(@PathVariable UUID id) { return orders.adminGet(id); }

  /** Turn an order down: the customer is told why and gets everything back (stock, coins, coupon, money). */
  @PostMapping("/{id}/reject")
  public OrderViews.AdminView reject(@PathVariable UUID id, @Valid @RequestBody RejectBody b) {
    return orders.adminReject(id, b.reason(), CurrentUser.id());
  }

  @PostMapping("/{id}/status")
  public OrderViews.AdminView status(@PathVariable UUID id, @Valid @RequestBody StatusBody b) {
    return orders.adminChangeStatus(id, b.status(), b.note(), CurrentUser.id());
  }
}
