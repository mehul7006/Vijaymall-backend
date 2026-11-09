package com.vijay.mall.order.api;

import java.util.Map;
import java.util.HashMap;
import com.vijay.mall.common.error.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.app.CartService;
import com.vijay.mall.order.domain.Cart;
import com.vijay.mall.wallet.domain.CoinRedemption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The saved basket (GET / PUT / DELETE /api/v1/cart). POST /api/v1/cart/quote stays in CartController. */
@RestController
@RequestMapping("/api/v1/cart")
public class CartBasketController {
  /**
   * One basket line as sent by the app.
   * @param productId the product (pack) id
   * @param qty units, 1 to {@link Cart#MAX_QTY}
   */
  public record LineBody(@NotNull UUID productId, @Min(1) @Max(Cart.MAX_QTY) int qty) {}

  /**
   * Body of the basket save call; it replaces the whole stored basket.
   * @param items the lines, at most {@link Cart#MAX_LINES}; repeated products are merged
   * @param couponCode optional coupon code
   * @param coinMode how coins are redeemed; {@code null} means none
   * @param percent share of the coins to use, 0 to 100, only meaningful for the percent mode
   */
  @Schema(name = "BasketBody")

  public record Body(@NotNull @Size(max = Cart.MAX_LINES) List<@Valid LineBody> items, String couponCode,
                     CoinRedemption.Mode coinMode, @Min(0) @Max(100) Integer percent) {}

  private final CartService carts;

  /** Creates the controller. */
  public CartBasketController(CartService carts) { this.carts = carts; }

  /** Returns the signed-in customer's saved basket; a customer without one gets an empty basket, not a 404. */
  @GetMapping
  public Cart get() { return carts.get(CurrentUser.id()); }

  /**
   * Replaces the saved basket with what was sent (last write wins) and returns the stored result.
   * <p>Fails with a validation error on {@code qty} when merged duplicates of one product exceed {@link Cart#MAX_QTY}.
   */
  @PutMapping
  public Cart put(@Valid @RequestBody Body b) {
    Map<UUID, Integer> totals = new HashMap<>();
    b.items().forEach(i -> totals.merge(i.productId(), i.qty(), Integer::sum));
    if (totals.values().stream().anyMatch(q -> q > Cart.MAX_QTY)) throw ApiException.validation("qty", "quantity must be 1 to " + Cart.MAX_QTY);
    var lines = b.items().stream().map(i -> new Cart.Line(i.productId(), i.qty())).toList();
    return carts.replace(CurrentUser.id(), lines, b.couponCode(), b.coinMode(), b.percent());
  }

  /** Empties the saved basket; answers {@code 204 No Content}, also when there was none. */
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete() { carts.clear(CurrentUser.id()); }
}
