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

/**
 * Customer endpoints for online orders: place, list, read and cancel their own orders. Any signed-in customer may call them.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
  /**
   * Body of the place-order call.
   * @param items the lines
   * @param couponCode optional coupon code
   * @param coinMode how coins are redeemed; {@code null} means none
   * @param percent share of the coins to use for the percent mode
   * @param fulfilment {@code DELIVERY} (default) or {@code PICKUP}
   * @param addressId the saved address; required for delivery
   * @param paymentMethod {@code COD} or {@code ONLINE} (credit and hybrid are counter-only)
   * @param expectedTotalPaise the total the customer saw in the quote, in paise; a different server total gives {@code 409 PRICE_CHANGED}
   * @param slot optional delivery or pickup time window, at most 40 characters
   */
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

  /** Creates the controller. */
  public OrderController(OrderService orders) { this.orders = orders; }

  /**
   * Places an order. The {@code Idempotency-Key} header is required: a retry with the same key returns the first order instead of creating another.
   * <p>Errors include {@code 409 PRICE_CHANGED} (with a fresh quote), validation errors for payment method, address or slot, and the stock check.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OrderViews.View place(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PlaceBody body) {
    return orders.place(CurrentUser.id(), key, body.toCommand());
  }

  /** Lists the signed-in customer's orders, newest first. */
  @GetMapping
  public Page<OrderViews.Summary> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return orders.list(CurrentUser.id(), page, size);
  }

  /**
   * Returns one of the customer's own orders with lines, timeline and payment session; {@code 404} for anyone else's order.
   */
  @GetMapping("/{id}")
  public OrderViews.View get(@PathVariable UUID id) { return orders.get(CurrentUser.id(), id); }

  /**
   * Cancels the customer's own order, with an optional reason. Allowed only before the shop starts packing; otherwise {@code 409 ILLEGAL_TRANSITION}. Stock, coins, coupon and money are given back.
   */
  @PostMapping("/{id}/cancel")
  public OrderViews.View cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelBody body) {
    return orders.customerCancel(CurrentUser.id(), id, body == null ? null : body.reason());
  }
}
