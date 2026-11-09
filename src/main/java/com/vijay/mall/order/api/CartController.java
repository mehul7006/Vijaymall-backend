package com.vijay.mall.order.api;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.wallet.domain.CoinRedemption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Price quote for a basket: the server is the only place that computes totals, discounts and delivery fee.
 * <p>Any signed-in customer may call it; nothing is stored.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {
  /**
   * One requested line.
   * @param productId the product (pack) id
   * @param qty requested units; limits are checked while pricing
   */
  public record ItemBody(@NotNull UUID productId, int qty) {}

  /**
   * Body of the quote call.
   * @param items the lines to price, at least one
   * @param couponCode optional coupon code
   * @param coinMode how coins are redeemed; {@code null} means none
   * @param percent share of the coins to use (default 100), for the percent mode
   * @param fulfilment {@code DELIVERY} (default) or {@code PICKUP}; pickup has no delivery fee
   */
  public record QuoteBody(@NotEmpty List<@Valid ItemBody> items, String couponCode, CoinRedemption.Mode coinMode,
                          Integer percent, Fulfilment fulfilment) {
    /** Converts the HTTP body into the request the quote steps work on. */
    public QuoteRequest toRequest() {
      return new QuoteRequest(items.stream().map(i -> new CatalogService.ItemRequest(i.productId(), i.qty())).toList(),
          couponCode, coinMode, percent, fulfilment);
    }
  }

  private final QuoteService quotes;

  /** Creates the controller. */
  public CartController(QuoteService quotes) { this.quotes = quotes; }

  /**
   * Prices the basket for the signed-in customer: line prices, flash sale prices, coupon, coins, delivery fee, total and the coins that would be earned. Warnings explain anything that could not be applied.
   */
  @PostMapping("/quote")
  public Quote quote(@Valid @RequestBody QuoteBody body) { return quotes.quote(CurrentUser.id(), body.toRequest()); }
}
