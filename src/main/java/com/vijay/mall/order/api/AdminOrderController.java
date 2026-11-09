package com.vijay.mall.order.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.order.domain.OrderStatus;
import com.vijay.mall.order.domain.OrderViews;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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

/**
 * Staff endpoints for working the online orders: list and search, open one, change its status, or reject it.
 * <p>Open to ADMIN and CASHIER (counter staff work the orders too; see {@code SecurityConfig}). The status rules and side
 * effects live in {@link com.vijay.mall.order.app.OrderService}.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {
  /**
   * Body of the status change.
   * @param status the status to move to; {@code PAYMENT_PENDING} cannot be chosen
   * @param note optional note kept in the order's status history
   */
  public record StatusBody(@NotNull OrderStatus status, @Size(max = 200) String note) {}

  /**
   * Body of a rejection.
   * @param reason why the order is turned down, 1 to 200 characters; shown to the customer
   */
  public record RejectBody(@NotNull @Size(min = 1, max = 200) String reason) {}

  private final OrderService orders;

  /** Creates the controller. */
  public AdminOrderController(OrderService orders) { this.orders = orders; }

  /**
   * Lists all orders, newest first, with the customer's name and contact details.
   * @param status optional order status to filter on; blank means every status
   * @param q optional part of the order number (for example {@code 1001})
   * @param page zero-based page number
   * @param size page size; clamped to the allowed maximum
   */
  @GetMapping
  public Page<OrderViews.AdminSummary> list(@RequestParam(required = false) String status,
                                            @Parameter(schema = @Schema(maxLength = 100)) @RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
    return orders.adminList(status, q, page, size);
  }

  /**
   * Returns one order with its lines, timeline and the customer's contact details; {@code 404 NOT_FOUND} if it does not exist.
   */
  @GetMapping("/{id}")
  public OrderViews.AdminView get(@PathVariable UUID id) { return orders.adminGet(id); }

  /** Turn an order down: the customer is told why and gets everything back (stock, coins, coupon, money). */
  @PostMapping("/{id}/reject")
  public OrderViews.AdminView reject(@PathVariable UUID id, @Valid @RequestBody RejectBody b) {
    return orders.adminReject(id, b.reason(), CurrentUser.id());
  }

  /**
   * Moves an order to another status. Repeating the current status is a no-op.
   * <p>Errors: {@code 409 ILLEGAL_TRANSITION} for a closed order, {@code 409 AWAITING_PAYMENT} for an unpaid online order that
   * is not being cancelled, {@code 409 OUT_OF_STOCK} when confirming and the shelf is short.
   */
  @PostMapping("/{id}/status")
  public OrderViews.AdminView status(@PathVariable UUID id, @Valid @RequestBody StatusBody b) {
    return orders.adminChangeStatus(id, b.status(), b.note(), CurrentUser.id());
  }
}
