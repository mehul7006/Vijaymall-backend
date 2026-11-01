package com.vijay.mall.order.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.OrderViews;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.wallet.domain.CoinRedemption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
  public record PlaceBody(@NotEmpty List< CartController.ItemBody> items, String couponCode,
                          CoinRedemption.Mode coinMode, Integer percent, Fulfilment fulfilment, UUID addressId,
                          @NotNull String paymentMethod, long expectedTotalPaise, @Size(max = 40) String slot) {
    OrderService.PlaceCommand toCommand() {
      QuoteRequest q = new CartController.QuoteBody(items, couponCode, coinMode, percent, fulfilment).toRequest();
      return new OrderService.PlaceCommand(q, addressId, paymentMethod, expectedTotalPaise, slot);
    }
  }

  /** The body is optional; the app sends the reason the customer picked. */
  public record CancelBody(@Size(max = 200) String reason) {}

  private final OrderService orders;

  public OrderController(OrderService orders) { this.orders = orders; }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OrderViews.View place(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PlaceBody body) {
    return orders.place(CurrentUser.id(), key, body.toCommand());
  }

  @GetMapping
  public Page<OrderViews.Summary> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return orders.list(CurrentUser.id(), page, size);
  }

  @GetMapping("/{id}")
  public OrderViews.View get(@PathVariable UUID id) { return orders.get(CurrentUser.id(), id); }

  @PostMapping("/{id}/cancel")
  public OrderViews.View cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelBody body) {
    return orders.customerCancel(CurrentUser.id(), id, body == null ? null : body.reason());
  }
}
