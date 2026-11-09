package com.vijay.mall.order.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.app.OrderReturnService;
import com.vijay.mall.order.app.OrderReturnService.ReturnView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Returns on delivered online orders: staff record them (admin and cashier), customers read their own. */
@RestController
@RequestMapping("/api/v1")
public class OrderReturnController {
  /**
   * Body of the return call.
   * @param reason why the goods come back, 1 to 200 characters
   * @param items the products and quantities, 1 to 100 entries
   */
  @Schema(name = "OrderReturnBody")
  public record ReturnBody(String reason, @NotEmpty List<OrderReturnService.ItemInput> items) {}

  private final OrderReturnService returns;

  /** Creates the controller. */
  public OrderReturnController(OrderReturnService returns) { this.returns = returns; }

  /** Needs an Idempotency-Key so a retried request cannot refund twice. */
  @PostMapping("/admin/orders/{id}/returns")
  @ResponseStatus(HttpStatus.CREATED)
  public ReturnView create(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReturnBody b) {
    return returns.create(CurrentUser.id(), id, key, new OrderReturnService.Input(b.reason(), b.items()));
  }

  /**
   * Previews a return: the exact refund, and per line the coupon and coin share that leaves with the returned pieces and any coupon
   * discount taken back. Same checks as recording the return; changes nothing and needs no Idempotency-Key.
   */
  @PostMapping("/admin/orders/{id}/returns/preview")
  public OrderReturnService.PreviewView preview(@PathVariable UUID id, @Valid @RequestBody ReturnBody b) {
    return returns.preview(id, new OrderReturnService.Input(b.reason(), b.items()));
  }

  /** Lists the returns recorded on any order, oldest first (ADMIN and CASHIER). */
  @GetMapping("/admin/orders/{id}/returns")
  public List<ReturnView> forStaff(@PathVariable UUID id) { return returns.forOrder(id, null); }

  /** The customer's own order only (404 for anyone else's). */
  @GetMapping("/orders/{id}/returns")
  public List<ReturnView> mine(@PathVariable UUID id) { return returns.forOrder(id, CurrentUser.id()); }
}
