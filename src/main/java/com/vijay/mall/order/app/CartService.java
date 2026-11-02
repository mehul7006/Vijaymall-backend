package com.vijay.mall.order.app;

import com.vijay.mall.order.domain.Cart;
import com.vijay.mall.order.infra.CartRepository;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The saved basket that follows a signed-in customer across devices. Pricing stays in QuoteService. */
@Service
public class CartService {
  private final CartRepository carts;
  private final Clock clock;

  public CartService(CartRepository carts, Clock clock) {
    this.carts = carts;
    this.clock = clock;
  }

  public Cart get(UUID userId) {
    Cart c = carts.find(userId);
    return c == null ? Cart.empty() : c;
  }

  /** Last write wins: the stored basket becomes exactly what was sent. Returns what is now stored. */
  @Transactional
  public Cart replace(UUID userId, List<Cart.Line> lines, String coupon, CoinRedemption.Mode mode, Integer percent) {
    carts.replace(userId, Cart.normalized(lines, coupon, mode, percent), clock.instant());
    return get(userId);
  }

  @Transactional
  public void clear(UUID userId) { carts.delete(userId); }
}
