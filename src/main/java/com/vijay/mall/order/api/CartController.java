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

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {
  public record ItemBody(@NotNull UUID productId, int qty) {}

  public record QuoteBody(@NotEmpty List<@Valid ItemBody> items, String couponCode, CoinRedemption.Mode coinMode,
                          Integer percent, Fulfilment fulfilment) {
    public QuoteRequest toRequest() {
      return new QuoteRequest(items.stream().map(i -> new CatalogService.ItemRequest(i.productId(), i.qty())).toList(),
          couponCode, coinMode, percent, fulfilment);
    }
  }

  private final QuoteService quotes;

  public CartController(QuoteService quotes) { this.quotes = quotes; }

  @PostMapping("/quote")
  public Quote quote(@Valid @RequestBody QuoteBody body) { return quotes.quote(CurrentUser.id(), body.toRequest()); }
}
