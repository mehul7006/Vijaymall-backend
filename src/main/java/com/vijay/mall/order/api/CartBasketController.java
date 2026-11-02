package com.vijay.mall.order.api;

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
  public record LineBody(@NotNull UUID productId, @Min(1) @Max(Cart.MAX_QTY) int qty) {}

  public record Body(@NotNull @Size(max = Cart.MAX_LINES) List<@Valid LineBody> items, String couponCode,
                     CoinRedemption.Mode coinMode, @Min(0) @Max(100) Integer percent) {}

  private final CartService carts;

  public CartBasketController(CartService carts) { this.carts = carts; }

  @GetMapping
  public Cart get() { return carts.get(CurrentUser.id()); }

  @PutMapping
  public Cart put(@Valid @RequestBody Body b) {
    var lines = b.items().stream().map(i -> new Cart.Line(i.productId(), i.qty())).toList();
    return carts.replace(CurrentUser.id(), lines, b.couponCode(), b.coinMode(), b.percent());
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete() { carts.clear(CurrentUser.id()); }
}
